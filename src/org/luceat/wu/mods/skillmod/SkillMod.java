package org.luceat.wu.mods.skillmod;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import javassist.*;
import javassist.bytecode.*;
import org.gotti.wurmunlimited.modloader.classhooks.HookException;
import org.gotti.wurmunlimited.modloader.classhooks.HookManager;
import org.gotti.wurmunlimited.modloader.interfaces.Configurable;
import org.gotti.wurmunlimited.modloader.interfaces.PreInitable;
import org.gotti.wurmunlimited.modloader.interfaces.WurmMod;


public class SkillMod implements WurmMod, Configurable, PreInitable {
    private boolean useSkillMod = true;
    private boolean removePriestPenalty = true;
    private Logger logger = Logger.getLogger(this.getClass().getName());
    HashMap<String, Float> skillFactors = new HashMap<>();


    @Override
    public void configure(Properties properties) {

        useSkillMod = Boolean.valueOf(properties.getProperty("useSkillMod", Boolean.toString(useSkillMod)));
        removePriestPenalty = Boolean.valueOf(properties.getProperty("removePriestPenalty", Boolean.toString(removePriestPenalty)));
        logger.log(Level.INFO, "useSkillMod: " + useSkillMod);
        logger.log(Level.INFO, "statFactor: " + removePriestPenalty);

        for (Map.Entry<Object, Object> entry : properties.entrySet())
        {
            String key = ((String) entry.getKey()).replace('_', ' ');
            if (!(  key.contentEquals("useSkillMod") ||
                    key.contentEquals("removePriestPenalty") ||
                    key.contentEquals("classpath") ||
                    key.contentEquals("classname"))) {
                Float value = new Float((String) entry.getValue());
                skillFactors.put(key, value);
            }
        }

        skillFactors.remove("useSkillMod");
        skillFactors.remove("removePriestPenalty");
    }

    @Override
    public void preInit() {
        if(useSkillMod)
            modifySkillSystem();
    }

    private void modifySkillSystem() {
        try{
            ClassPool cp = HookManager.getInstance().getClassPool();
            CtClass skillSystem = cp.get("com.wurmonline.server.skills.SkillSystem");
            CtConstructor staticBlock = skillSystem.getClassInitializer();

            MethodInfo mi = staticBlock.getMethodInfo();
            CodeAttribute ca = mi.getCodeAttribute();
            ConstPool constPool= ca.getConstPool();

            CodeIterator codeIterator = ca.iterator();

            boolean modifyNextLDC = false;
            String currentSkill = "no skill set!";
            Float skillFactor = 1.0F;
            int wsCount = 0;

            while(codeIterator.hasNext()) {

                int pos = codeIterator.next();
                int op = codeIterator.byteAt(pos);
                //If it is a string and also a skill
                if (op == CodeIterator.LDC) {
                    int constRef =  codeIterator.byteAt(pos+1);
                    Object ldcValue = constPool.getLdcValue(constRef);
                    if(ldcValue instanceof String) {
                        String ldcString = (String) ldcValue;
                        skillFactor = skillFactors.get(ldcString);
                        if (skillFactor != null){
                            modifyNextLDC = true;
                            currentSkill = ldcString;
                        }
                    }
                    //Else, change the difficulty
                    else if (ldcValue instanceof Float){
                        Float ldcFloat = (Float) ldcValue;
                        if(modifyNextLDC){
                            modifyNextLDC = false;
                            float newLdcFloat = (ldcFloat / skillFactor);
                            int newRef = constPool.addFloatInfo(newLdcFloat);

                            //Unclear if 256 is the true break for switching LDC_W. Be careful if you copy this.
                            if( newRef < 256) {
                                codeIterator.writeByte(Bytecode.LDC, pos);
                                codeIterator.writeByte(newRef, pos + 1);
                            } else {
                                codeIterator.insertGap(pos, 1);
                                codeIterator.writeByte(Bytecode.LDC_W, pos);
                                codeIterator.write16bit(newRef, pos+1);
                            }
                            logger.log(Level.INFO, "Modified skill " + currentSkill + " it now has difficulty " + newLdcFloat);

                        }
                    }

                }
                //Some floats are already picked up by op LDC_W
                else if(op == CodeIterator.LDC_W && modifyNextLDC){
                    int constRef = codeIterator.u16bitAt(pos+1);

                    Object ldcValue = constPool.getLdcValue(constRef);
                    if(ldcValue instanceof Float){

                        float newLdcFloat = ((Float) ldcValue / skillFactor);
                        int newRef = constPool.addFloatInfo(newLdcFloat);

                        codeIterator.writeByte(Bytecode.LDC_W, pos);
                        codeIterator.write16bit(newRef, pos+1);
                        modifyNextLDC = false;
                    }
                }
                //This is weapon smithing :<
                else if(op == CodeIterator.LDC2_W && modifyNextLDC){

                    if(currentSkill.contentEquals("Weapon smithing")) {
                        wsCount++;
                        logger.log(Level.INFO, "Modifying special skill Weapon smithing");
                        int constRef = codeIterator.u16bitAt(pos+1);
                        Object ldcValue = constPool.getLdcValue(constRef);
                        if(ldcValue instanceof Long){

                            Float newLdcFloat = ( ((Long) ldcValue).floatValue() / skillFactor);
                            int newRef = constPool.addLongInfo(newLdcFloat.longValue());

                            codeIterator.writeByte(Bytecode.LDC2_W, pos);
                            codeIterator.write16bit(newRef, pos+1);
                            modifyNextLDC = false;
                        }
                        if (wsCount < 2)
                            modifyNextLDC = true;
                    }
                }
                else if(op == CodeIterator.PUTFIELD && removePriestPenalty){
                    //Sets priestpenalty to false instead of true.
                    codeIterator.writeByte(Bytecode.ICONST_0, pos-1);
                    logger.log(Level.INFO, "Removed a priest penalty.");
                }
            }

            mi.rebuildStackMap(cp);


        } catch (NotFoundException e) {
            throw new HookException(e);
        } catch (BadBytecode e) {
            e.printStackTrace();
        }
    }
}