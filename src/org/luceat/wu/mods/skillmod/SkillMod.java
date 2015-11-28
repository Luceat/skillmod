package org.luceat.wu.mods.skillmod;

import java.io.IOException;
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
    private double lowerStatDivider = 5.0D;
    private double upperStatDivider = 45.0D;
    private final String modifyFightSkillMethodName = "modifyFightSkill";
    private final String modifyFightSkillMethodDesc = "()Z";
    private final String checkAdvanceMethodName = "checkAdvance";
    private final String checkAdvanceMethodDesc = "(DLcom/wurmonline/server/items/Item;DZFZD)D";
    private final String setKnowledgeMethodName = "setKnowledge";
    private Logger logger = Logger.getLogger(this.getClass().getName());
    HashMap<String, Float> skillFactors = new HashMap<>();


    @Override
    public void configure(Properties properties) {

        useSkillMod = Boolean.valueOf(properties.getProperty("useSkillMod", Boolean.toString(useSkillMod)));
        removePriestPenalty = Boolean.valueOf(properties.getProperty("removePriestPenalty", Boolean.toString(removePriestPenalty)));
        lowerStatDivider = Double.valueOf(properties.getProperty("lowerStatDivider", Double.toString(lowerStatDivider)));
        upperStatDivider = Double.valueOf(properties.getProperty("upperStatDivider", Double.toString(upperStatDivider)));
        logger.log(Level.INFO, "useSkillMod: " + useSkillMod);
        logger.log(Level.INFO, "statFactor: " + removePriestPenalty);

        for (Map.Entry<Object, Object> entry : properties.entrySet())
        {
            String key = ((String) entry.getKey()).replace('_', ' ');
            if (!(  key.contentEquals("useSkillMod") ||
                    key.contentEquals("removePriestPenalty") ||
                    key.contentEquals("classpath") ||
                    key.contentEquals("classname") || 
                    key.contentEquals("lowerStatDivider") ||
                    key.contentEquals("upperStatDivider"))) {
                Float value = new Float((String) entry.getValue());
                skillFactors.put(key, value);
            }
        }

        skillFactors.remove("useSkillMod");
        skillFactors.remove("removePriestPenalty");
    }

    @Override
    public void preInit() {
        if(useSkillMod) {
            modifySkillSystem();

            Float fightingFactor = skillFactors.get("Fighting");
            if (fightingFactor != null && fightingFactor != 1.0F){
                logger.log(Level.INFO, "Fighting factor is: " + fightingFactor);
                modifyFightingSkillGain((double) fightingFactor);
            }
            if (lowerStatDivider != 5.0D || upperStatDivider != 45.0D){
                changeStatDividers();
            }
        }
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
                        if (skillFactor != null && skillFactor != 1.0F){
                            modifyNextLDC = true;
                            currentSkill = ldcString;
                        }
                    }
                    //Else, change the difficulty
                    else if (ldcValue instanceof Float && modifyNextLDC){
                        Float ldcFloat = (Float) ldcValue;
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
                //Some floats are already picked up by op LDC_W
                else if(op == CodeIterator.LDC_W){
                    int constRef = codeIterator.u16bitAt(pos+1);

                    Object ldcValue = constPool.getLdcValue(constRef);
                    if(ldcValue instanceof String) {
                        String ldcString = (String) ldcValue;
                        skillFactor = skillFactors.get(ldcString);
                        if (skillFactor != null && skillFactor != 1.0F){
                            modifyNextLDC = true;
                            currentSkill = ldcString;
                        }
                    }
                    else if(ldcValue instanceof Float && modifyNextLDC){

                        float newLdcFloat = ((Float) ldcValue / skillFactor);
                        int newRef = constPool.addFloatInfo(newLdcFloat);

                        codeIterator.writeByte(Bytecode.LDC_W, pos);
                        codeIterator.write16bit(newRef, pos+1);
                        modifyNextLDC = false;
                        logger.log(Level.INFO, "Modified skill " + currentSkill + " it now has difficulty " + newLdcFloat);
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
                            logger.log(Level.INFO, "Modified skill " + currentSkill + " it now has difficulty " + newLdcFloat);
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

    private void modifyFightingSkillGain(double skillFactor){
        try{
            logger.log(Level.INFO, "Going to modify fighting skill gain with factor: " + skillFactor);
            ClassPool cp = HookManager.getInstance().getClassPool();
            CtClass creatureClass = cp.get("com.wurmonline.server.creatures.Creature");

            MethodInfo mi = creatureClass.getMethod(modifyFightSkillMethodName, modifyFightSkillMethodDesc).getMethodInfo();
            CodeAttribute ca = mi.getCodeAttribute();
            ConstPool constPool= ca.getConstPool();
            int ref = constPool.addDoubleInfo(skillFactor);

            CodeIterator codeIterator = ca.iterator();


            while(codeIterator.hasNext()) {

                int pos = codeIterator.next();
                int op = codeIterator.byteAt(pos);
                int ldcRef = codeIterator.u16bitAt(pos+1);
                int nextOp = codeIterator.byteAt(pos+3);

                //Try to find the bytecode-pattern
                if (op == CodeIterator.LDC2_W && nextOp == CodeIterator.DMUL) {
                    Object ldcObject = constPool.getLdcValue(ldcRef);

                    if (!(ldcObject instanceof Double))
                        continue;

                    double ldcValue = (double) ldcObject;

                    logger.log(Level.INFO, "Found ldcValue of: " + ldcValue);
                    if(ldcValue  == 0.25D ) {
                        //Insert gap before the call and add instructions:
                        //LDC2_W refToDouble 1 + 2 bytes
                        //DMUL 1 byte
                        logger.log(Level.INFO, "Found bytecode pattern where to inject fightFactor");
                        codeIterator.insertGap(pos + 4, 4);
                        codeIterator.writeByte(Bytecode.LDC2_W, pos + 4);
                        codeIterator.write16bit(ref, pos + 5);
                        codeIterator.writeByte(Bytecode.DMUL, pos + 7);
                        logger.log(Level.INFO, "Finished injecting");
                        break;
                    }
                }
            }

            mi.rebuildStackMap(cp);


        } catch (NotFoundException e) {
            throw new HookException(e);
        } catch (BadBytecode e) {
            e.printStackTrace();
        }
    }
    
    private void changeStatDividers(){
        try{
            logger.log(Level.INFO, "Going to modify stat divider below 31 to: " + lowerStatDivider + ", and stat modifier above 31 to: " + upperStatDivider);
            ClassPool cp = HookManager.getInstance().getClassPool();
            CtClass skillClass = cp.get("com.wurmonline.server.skills.Skill");

            MethodInfo mi = skillClass.getMethod(checkAdvanceMethodName, checkAdvanceMethodDesc).getMethodInfo();
            CodeAttribute ca = mi.getCodeAttribute();
            ConstPool constPool= ca.getConstPool();
            int ref1 = constPool.addDoubleInfo(lowerStatDivider);
            int ref2 = constPool.addDoubleInfo(upperStatDivider);
            boolean doneLower = false;
            boolean doneUpper = false;

            CodeIterator codeIterator = ca.iterator();
            while(codeIterator.hasNext()) {

                int pos = codeIterator.next();
                int op = codeIterator.byteAt(pos);
                int ldcRef = codeIterator.u16bitAt(pos+1);
                int nextOp = codeIterator.byteAt(pos+3);

                //Try to find the bytecode-pattern
                if (op == CodeIterator.LDC2_W && nextOp == CodeIterator.DSTORE) {
                    Object ldcObject = constPool.getLdcValue(ldcRef);

                    if (!(ldcObject instanceof Double))
                        continue;

                    double ldcValue = (double) ldcObject;

                    logger.log(Level.INFO, "Found ldcValue of: " + ldcValue);
                    if(ldcValue  == 5.0D ) {
                        logger.log(Level.INFO, "Found bytecode pattern where to replace lower stat divider");
                        codeIterator.write16bit(ref1, pos + 1);
                        logger.log(Level.INFO, "Injected Lower");
                        doneLower = true;
                    }
                    else if(ldcValue  == 45.0D ) {
                        logger.log(Level.INFO, "Found bytecode pattern where to replace upper stat divider");
                        codeIterator.write16bit(ref2, pos + 1);
                        logger.log(Level.INFO, "Injected Upper");
                        doneUpper = true;
                    }
                    if (doneLower && doneUpper){
                        break;
                    }
                }
            }
            mi.rebuildStackMap(cp);
        }
        catch(NotFoundException e)
        {
            throw new HookException(e);
        }
        catch(BadBytecode e){
            e.printStackTrace();
        }
    }
}