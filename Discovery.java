import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtMethod;
import javassist.Modifier;
import javassist.NotFoundException;
import javassist.bytecode.BadBytecode;
import javassist.bytecode.CodeAttribute;
import javassist.bytecode.CodeIterator;
import javassist.bytecode.ConstPool;
import javassist.bytecode.Opcode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

class Discovery {
    static final String CTX = "com.threerings.projectx.util.w";
    static final String PARTY_OBJECT = "com.threerings.projectx.dungeon.data.PartyObject";
    static final String DAMAGE_EVENT = PARTY_OBJECT + "$DamageEvent";
    static final String DOBJECT = "com.threerings.presents.dobj.DObject";
    static final String PLACE_OBJECT = "com.threerings.crowd.data.PlaceObject";
    static final String OCCUPANT_INFO = "com.threerings.projectx.data.ProjectXOccupantInfo";
    static final String INSETS = "com.threerings.opengl.gui.e.c";
    static final String LABEL = "com.threerings.opengl.gui.Label";
    static final String CONTAINER = "com.threerings.opengl.gui.Container";
    static final String COLOR4F = "com.threerings.opengl.renderer.Color4f";
    static final String RENDERER = "com.threerings.opengl.renderer.Renderer";
    static final String MOUSE_EVENT = "com.threerings.opengl.gui.event.MouseEvent";
    static final String DSET = "com.threerings.presents.dobj.DSet";
    static final String HEALTH = "com.threerings.projectx.dungeon.data.Health";
    static final String PARTY_MEMBER = "com.threerings.projectx.dungeon.data.PartyMember";
    static final String CONFIG_MANAGER = "com.threerings.config.ConfigManager";
    static final String CONFIG_REF = "com.threerings.config.ConfigReference";
    static final String COLOR_POSITORY = "com.threerings.media.image.ColorPository";
    static final String MANAGED_CONFIG = "com.threerings.config.ManagedConfig";
    static final String CONFIG_GROUP = "com.threerings.config.ConfigGroup";
    static final String ARGUMENT_MAP = "com.threerings.config.ArgumentMap";
    static final String WEARER_ARG = "Colorization";
    static final String PLAYER_OBJECT = "com.threerings.projectx.data.PlayerObject";
    static final String LEVEL_ITEM = "com.threerings.projectx.item.data.LevelItem";
    static final String HELM_PICK = "0, -1, 3";
    static final String LEVEL_PARTY = "com.threerings.projectx.dungeon.data.LevelPartyObject";
    static final String MISSION_PARTY = "com.threerings.projectx.dungeon.data.MissionPartyObject";
    static final String PLAYER_EQUIP = "com.threerings.projectx.data.PlayerEquipment";
    static final String HELM_SLOT = "0";
    static final String ACTOR = "com.threerings.tudey.data.actor.Actor";
    static final String MONSTER = "com.threerings.projectx.dungeon.data.actor.Monster";
    static final String INT_MAP = "com.samskivert.util.HashIntMap";
    static final String VECTOR2F = "com.threerings.math.Vector2f";
    static final String ZOMBIE_HEAD = "Character/NPC/Monster/Undead/Parts/Zombie Head";
    static final String ACTOR_CONFIG = "com.threerings.tudey.config.ActorConfig";
    static final String ACTOR_DERIVED = "com.threerings.tudey.config.ActorConfig$Derived";
    static final String LOOT_ARG = "Loot";
    static final String ITEM = "com.threerings.projectx.item.data.Item";
    static final String ITEM_CONFIG = "com.threerings.projectx.item.config.ItemConfig";
    static final String ITEM_ORIGINAL = ITEM_CONFIG + "$Original";

    static final String WHITE_IMAGE = "particle/texture/blank_white.png";
    static final String TEXT_STYLE = "Default/ChatOverlayText";

    static final String[] EVENT_FIELDS = {"_timestamp", "_actorId", "_amount", "_sourceId"};

    static class Discovered {
        String dirBaseClass;
        String dirCtxField;
        String objectReadyMethod;
        String currentObjMethod;
        String objectReadyDesc;

        String damageIface;
        String damageMethod;
        String listenerBaseIface;
        String addListenerMethod;
        String removeListenerMethod;

        String crowdCtxIface;
        String locationDirMethod;
        String locationDirClass;
        String placeObjectMethod;

        String componentClass;
        String windowClass;
        String rootClass;
        String dimensionClass;
        String keptWindowIface;
        String tickableIface;
        String backgroundBaseClass;
        String imageClass;
        String colorizationClass;
        String glCtxClass;
        String mouseAdapterClass;
        String itemResolveMethod;
        String itemOwnConfigMethod;
        String selfMethod;
        String visibleItemMethod;
        String oidMethod;
        String itemGroupMethod;
        String itemGroupsMethod;
        String groupListMethod;
        String configOwnOriginalMethod;
        String refArgsMethod;
        String sceneViewClass;
        String spriteByIdMethod;
        String spriteClass;
        String spriteActorMethod;
        String actorRefMethod;
        String spriteMapField;
        String actorWhereMethod;
        String modelClass;
        String colorStateClass;
        String spriteGoneField;
        String cfgByNameMethod;
        String colorLookupMethod;
        String colorizedRootMethod;
    }

    // names are obfuscated and change between builds, so everything is found by shape and the
    // build fails if anything is ambiguous
    static Discovered discover(ClassPool pool, String pcodeJar) throws Exception {
        Discovered d = new Discovered();

        for (String n : new String[]{CTX, PARTY_OBJECT, DAMAGE_EVENT, DOBJECT, PLACE_OBJECT,
                OCCUPANT_INFO, LABEL, CONTAINER, COLOR4F, RENDERER, MOUSE_EVENT, DSET, HEALTH,
                PARTY_MEMBER, CONFIG_MANAGER, CONFIG_REF, COLOR_POSITORY,
                ITEM, ITEM_CONFIG, ITEM_ORIGINAL, LEVEL_PARTY, MISSION_PARTY, PLAYER_EQUIP,
                PLAYER_OBJECT, LEVEL_ITEM, CONFIG_GROUP, ARGUMENT_MAP, MANAGED_CONFIG,
                ACTOR, ACTOR_CONFIG, ACTOR_DERIVED,
                INSETS}) {
            pool.get(n);
        }

        List<String> gui = classesUnder(pcodeJar, "com.threerings.opengl.gui.");
        CtClass ctx = pool.get(CTX);

        CtClass dirBase = uniqueMethod("dungeonDirClass", ctx,
            m -> returnName(m).startsWith("com.threerings.projectx.dungeon.client.")
                && m.getParameterTypes().length == 0).getReturnType().getSuperclass();
        d.dirBaseClass = dirBase.getName();
        d.dirCtxField = uniqueField("dirCtxField", dirBase,
            f -> typeName(f).equals(CTX)).getName();

        d.objectReadyDesc = "(L" + slash(DOBJECT) + ";)V";
        d.objectReadyMethod = uniqueMethod("objectReadyMethod", dirBase,
            m -> Modifier.isPublic(m.getModifiers())
                && m.getMethodInfo().getDescriptor().equals(d.objectReadyDesc)).getName();

        d.currentObjMethod = uniqueMethod("currentObjMethod", dirBase,
            m -> m.getParameterTypes().length == 0
                && returnName(m).equals(DOBJECT)).getName();

        CtClass party = pool.get(PARTY_OBJECT);
        CtMethod damage = null;
        CtClass damageIface = null;
        for (CtClass nested : party.getNestedClasses()) {
            if (!nested.isInterface()) continue;
            for (CtMethod m : nested.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 1) continue;
                if (!m.getParameterTypes()[0].getName().equals(DAMAGE_EVENT)) continue;
                if (damage != null) {
                    throw new RuntimeException("Ambiguous damageIface: " + damageIface.getName()
                        + " / " + nested.getName());
                }
                damage = m;
                damageIface = nested;
            }
        }
        if (damage == null) throw new RuntimeException("Cannot resolve damageIface in " + PARTY_OBJECT);
        d.damageIface = damageIface.getName();
        d.damageMethod = damage.getName();
        CtClass[] above = damageIface.getInterfaces();
        if (above.length != 1) {
            throw new RuntimeException("Expected one listener base above " + d.damageIface);
        }
        d.listenerBaseIface = above[0].getName();

        for (String f : EVENT_FIELDS) {
            declared(pool.get(DAMAGE_EVENT), f);
        }
        declared(party, "depth");
        declared(party, "visited");
        for (String f : new String[]{"floor", "actualGateId", "difficulty"}) {
            declared(pool.get(LEVEL_PARTY), f);
        }
        declared(pool.get(MISSION_PARTY), "missionId");
        declared(pool.get(PLAYER_OBJECT), "partyOid");
        declared(party, "health");
        for (String f : new String[]{"current", "maximum"}) {
            declared(pool.get(HEALTH), f);
        }
        for (String f : new String[]{"pawnId", "username", "bodyOid"}) {
            declared(hierarchy(pool.get(OCCUPANT_INFO)), f, OCCUPANT_INFO);
        }
        declared(pool.get(PLACE_OBJECT), "occupantInfo");
        declared(party, "members");
        for (String f : new String[]{"name", "weapons"}) {
            declared(pool.get(PARTY_MEMBER), f);
        }
        declared(pool.get(ITEM_ORIGINAL), "icon");
        d.itemOwnConfigMethod = uniqueMethod("itemOwnConfigMethod", pool.get(ITEM), m ->
            !Modifier.isStatic(m.getModifiers())
                && m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals(CONFIG_MANAGER)
                && returnName(m).equals(ITEM_ORIGINAL)).getName();
        declared(pool.get(PLAYER_EQUIP), "items");
        declared(pool.get(LEVEL_PARTY), "equipment");

        d.selfMethod = uniqueMethod("selfMethod", pool.get(CTX), m ->
            m.getParameterTypes().length == 0
                && returnName(m).equals(PLAYER_OBJECT)).getName();
        d.visibleItemMethod = uniqueMethod("visibleItemMethod", pool.get(PLAYER_OBJECT), m -> {
            CtClass[] p = m.getParameterTypes();
            return !Modifier.isStatic(m.getModifiers()) && p.length == 4
                && p[0].getName().equals("int") && p[1].getName().equals("int")
                && p[2].getName().equals("int") && p[3].getName().equals("java.lang.Boolean")
                && returnName(m).equals(LEVEL_ITEM);
        }).getName();
        d.itemGroupMethod = uniqueMethod("itemGroupMethod", pool.get(CONFIG_MANAGER), m ->
            m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals("java.lang.Class")
                && returnName(m).equals(CONFIG_GROUP)).getName();
        d.itemGroupsMethod = uniqueMethod("itemGroupsMethod", pool.get(CONFIG_MANAGER), m ->
            m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals("java.lang.Class")
                && returnName(m).equals(CONFIG_GROUP + "[]")).getName();
        d.groupListMethod = uniqueMethod("groupListMethod", pool.get(CONFIG_GROUP), m ->
            m.getParameterTypes().length == 0
                && returnName(m).equals("java.lang.Iterable")
                && m.getGenericSignature() != null
                && m.getGenericSignature().contains("TT;")).getName();
        d.configOwnOriginalMethod = uniqueMethod("configOwnOriginalMethod", pool.get(ITEM_CONFIG),
            m -> !Modifier.isStatic(m.getModifiers())
                && m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals(CONFIG_MANAGER)
                && returnName(m).equals(ITEM_ORIGINAL)).getName();
        CtClass actorCt = pool.get(ACTOR);
        String[] view = new String[3];
        for (String name : classesUnder(pcodeJar, "com.threerings.tudey.")) {
            CtClass c;
            try {
                c = pool.get(name);
            } catch (NotFoundException e) {
                continue;
            }
            if (c.isInterface()) continue;
            for (CtMethod m : c.getDeclaredMethods()) {
                if (!Modifier.isPublic(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) {
                    continue;
                }
                CtClass[] params;
                CtClass ret;
                try {
                    params = m.getParameterTypes();
                    ret = m.getReturnType();
                } catch (NotFoundException e) {
                    continue;
                }
                if (params.length != 1 || !params[0].getName().equals("int")) continue;
                if (ret.isPrimitive() || ret.isInterface() || ret.isArray()) continue;
                if (ret.getName().equals(ACTOR)) continue;
                CtMethod holds = uniqueOrNull(ret, g -> {
                    try {
                        return Modifier.isPublic(g.getModifiers())
                            && g.getParameterTypes().length == 0
                            && g.getReturnType().getName().equals(ACTOR)
                            && plainFieldRead(g);
                    } catch (NotFoundException e) {
                        return false;
                    }
                });
                if (holds == null) continue;
                if (view[0] != null && !view[0].equals(c.getName())) {
                    throw new RuntimeException("Ambiguous sceneViewClass: " + view[0]
                        + " / " + c.getName());
                }
                view[0] = c.getName();
                view[1] = m.getName();
                view[2] = holds.getName();
                d.spriteClass = ret.getName();
            }
        }
        if (view[0] == null) throw new RuntimeException("Cannot resolve sceneViewClass");
        d.sceneViewClass = view[0];
        d.spriteByIdMethod = view[1];
        d.spriteActorMethod = view[2];

        String spriteSig = "L" + d.spriteClass.replace('.', '/') + ";";
        CtField spriteMap = null;
        for (CtField f : pool.get(d.sceneViewClass).getDeclaredFields()) {
            String sig = f.getGenericSignature();
            if (Modifier.isStatic(f.getModifiers()) || sig == null) continue;
            if (!f.getType().getName().equals(INT_MAP) || !sig.contains(spriteSig)) continue;
            if (spriteMap != null) {
                throw new RuntimeException("Ambiguous spriteMapField: " + spriteMap.getName()
                    + " / " + f.getName());
            }
            spriteMap = f;
        }
        if (spriteMap == null) throw new RuntimeException("Cannot resolve spriteMapField");
        d.spriteMapField = spriteMap.getName();

        CtClass modelCt = pool.get(d.spriteClass).getField("_model").getType();
        d.modelClass = modelCt.getName();
        d.colorStateClass = uniqueMethod("getColorState", modelCt, m -> {
            try {
                return m.getName().equals("getColorState") && m.getParameterTypes().length == 0;
            } catch (NotFoundException e) {
                return false;
            }
        }).getReturnType().getName();

        // remove(int) only stores the removal time
        CtMethod gone = pool.get(d.spriteClass).getMethod("remove", "(I)V");
        d.spriteGoneField = fieldWrittenBy(gone);
        if (d.spriteGoneField == null) throw new RuntimeException("Cannot resolve spriteGoneField");

        declared(actorCt, "_created");

        d.actorWhereMethod = uniqueMethod("actorWhereMethod", actorCt, m -> {
            try {
                return Modifier.isPublic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && m.getReturnType().getName().equals(VECTOR2F);
            } catch (NotFoundException e) {
                return false;
            }
        }).getName();

        d.actorRefMethod = uniqueMethod("actorRefMethod", actorCt, m -> {
            try {
                return Modifier.isPublic(m.getModifiers())
                    && m.getParameterTypes().length == 0
                    && m.getReturnType().getName().equals(CONFIG_REF);
            } catch (NotFoundException e) {
                return false;
            }
        }).getName();

        d.cfgByNameMethod = uniqueMethod("cfgByNameMethod", pool.get(CONFIG_MANAGER), m -> {
            try {
                return Modifier.isPublic(m.getModifiers()) && Modifier.isFinal(m.getModifiers())
                    && m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0].getName().equals("java.lang.Class")
                    && m.getParameterTypes()[1].getName().equals("java.lang.String")
                    && m.getReturnType().getName().equals(MANAGED_CONFIG);
            } catch (NotFoundException e) {
                return false;
            }
        }).getName();

        declared(pool.get(ACTOR_CONFIG), "implementation");
        declared(pool.get(ACTOR_DERIVED), "actor");

        d.refArgsMethod = uniqueMethod("refArgsMethod", pool.get(CONFIG_REF), m ->
            m.getParameterTypes().length == 0
                && returnName(m).equals(ARGUMENT_MAP)).getName();

        d.oidMethod = uniqueMethod("oidMethod", pool.get(DOBJECT), m ->
            m.getParameterTypes().length == 0 && returnName(m).equals("int")).getName();
        declared(pool.get(PLAYER_EQUIP), "visibleEquipment");

        CtClass dobj = pool.get(DOBJECT);
        String oneArg = "(L" + slash(d.listenerBaseIface) + ";)V";
        String twoArg = "(L" + slash(d.listenerBaseIface) + ";Z)V";
        d.addListenerMethod = uniqueMethod("addListenerMethod", dobj,
            m -> m.getMethodInfo().getDescriptor().equals(twoArg)).getName();
        d.removeListenerMethod = uniqueMethod("removeListenerMethod", dobj,
            m -> m.getMethodInfo().getDescriptor().equals(oneArg)
                && !callsDescriptor(m, twoArg)).getName();

        String[] found = new String[4];
        for (CtClass iface : allInterfaces(ctx)) {
            for (CtMethod m : iface.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                CtClass ret;
                try {
                    ret = m.getReturnType();
                } catch (NotFoundException e) {
                    continue;
                }
                if (ret.isPrimitive() || ret.isInterface()) continue;
                CtMethod getter = uniqueOrNull(ret,
                    g -> g.getParameterTypes().length == 0 && returnName(g).equals(PLACE_OBJECT));
                if (getter == null) continue;
                if (found[0] != null) {
                    throw new RuntimeException("Ambiguous locationDirMethod: " + found[1]
                        + " / " + m.getName());
                }
                found[0] = iface.getName();
                found[1] = m.getName();
                found[2] = getter.getName();
                found[3] = ret.getName();
            }
        }
        if (found[0] == null) throw new RuntimeException("Cannot resolve locationDirMethod");
        d.crowdCtxIface = found[0];
        d.locationDirMethod = found[1];
        d.placeObjectMethod = found[2];
        d.locationDirClass = found[3];

        CtClass root = unique("rootClass", pool, gui, c ->
            method(c, "addWindow") != null && method(c, "addTickParticipant") != null);
        d.rootClass = root.getName();
        d.windowClass = uniqueMethod("windowClass", root,
            m -> m.getName().equals("addWindow") && m.getParameterTypes().length == 1)
            .getParameterTypes()[0].getName();
        d.tickableIface = method(root, "addTickParticipant").getParameterTypes()[0].getName();

        CtClass container = pool.get(CONTAINER);
        d.componentClass = container.getSuperclass().getName();
        CtClass component = pool.get(d.componentClass);
        d.backgroundBaseClass = uniqueMethod("backgroundBaseClass", component,
            m -> m.getName().equals("setBackground") && m.getParameterTypes().length == 2)
            .getParameterTypes()[1].getName();
        d.dimensionClass = uniqueMethod("dimensionClass", component,
            m -> m.getName().equals("computePreferredSize")).getReturnType().getName();
        d.keptWindowIface = sweepExemption(pool, pcodeJar);

        CtClass label = pool.get(LABEL);
        d.glCtxClass = label.getDeclaredConstructors()[0].getParameterTypes()[0].getName();
        CtMethod getImage = uniqueMethod("imageCacheMethod",
            pool.get(method(pool.get(d.glCtxClass), "getImageCache").getReturnType().getName()),
            m -> m.getName().equals("getImage"));
        d.imageClass = getImage.getReturnType().getName();
        d.colorizationClass = getImage.getParameterTypes()[1].getComponentType().getName();

        d.itemResolveMethod = uniqueMethod("itemResolveMethod", pool.get(ITEM), m ->
            Modifier.isStatic(m.getModifiers())
                && m.getParameterTypes().length == 2
                && m.getParameterTypes()[0].getName().equals(CONFIG_MANAGER)
                && m.getParameterTypes()[1].getName().equals(CONFIG_REF)
                && returnName(m).equals(ITEM_ORIGINAL)).getName();

        declared(pool.get(PARTY_MEMBER), "color");
        method(pool.get(d.glCtxClass), "getColorPository");
        d.colorLookupMethod = uniqueMethod("colorLookupMethod", pool.get(COLOR_POSITORY), m ->
            m.getParameterTypes().length == 1
                && m.getParameterTypes()[0].getName().equals("int")
                && returnName(m).equals(d.colorizationClass)).getName();
        d.colorizedRootMethod = uniqueMethod("colorizedRootMethod", pool.get(d.colorizationClass),
            m -> m.getParameterTypes().length == 0
                && returnName(m).equals("java.awt.Color")).getName();

        d.mouseAdapterClass = unique("mouseAdapterClass", pool,
            classesUnder(pcodeJar, "com.threerings.opengl.gui.event."), c ->
                !c.isInterface() && method(c, "mouseClicked") != null
                    && method(c, "mouseDragged") != null).getName();

        return d;
    }

    static String slash(String name) {
        return name.replace('.', '/');
    }

    static String returnName(CtMethod m) {
        try {
            return m.getReturnType().getName();
        } catch (NotFoundException e) {
            return "";
        }
    }

    static String typeName(CtField f) {
        try {
            return f.getType().getName();
        } catch (NotFoundException e) {
            return "";
        }
    }

    static boolean callsDescriptor(CtMethod m, String desc) throws BadBytecode {
        CodeAttribute code = m.getMethodInfo().getCodeAttribute();
        if (code == null) return false;
        ConstPool cp = code.getConstPool();
        CodeIterator it = code.iterator();
        while (it.hasNext()) {
            int at = it.next();
            int op = it.byteAt(at);
            if (op != Opcode.INVOKEVIRTUAL && op != Opcode.INVOKESPECIAL
                && op != Opcode.INVOKESTATIC) {
                continue;
            }
            int ref = it.u16bitAt(at + 1);
            if (desc.equals(cp.getMethodrefType(ref))) return true;
        }
        return false;
    }

    static List<CtClass> hierarchy(CtClass cls) throws NotFoundException {
        List<CtClass> out = new ArrayList<CtClass>();
        for (CtClass at = cls; at != null; at = at.getSuperclass()) {
            out.add(at);
        }
        return out;
    }

    static Set<CtClass> allInterfaces(CtClass cls) throws NotFoundException {
        Set<CtClass> out = new LinkedHashSet<CtClass>();
        gather(cls, out);
        return out;
    }

    private static void gather(CtClass cls, Set<CtClass> out) throws NotFoundException {
        if (cls == null) return;
        for (CtClass i : cls.getInterfaces()) {
            if (out.add(i)) gather(i, out);
        }
        gather(cls.getSuperclass(), out);
    }

    static CtClass unique(String what, ClassPool pool, List<String> names, Predicate<CtClass> p) {
        CtClass found = null;
        for (String n : names) {
            CtClass c;
            try {
                c = pool.get(n);
                if (!p.test(c)) continue;
            } catch (Exception e) {
                continue;
            }
            if (found != null) {
                throw new RuntimeException("Ambiguous " + what + ": " + found.getName()
                    + " / " + c.getName());
            }
            found = c;
        }
        if (found == null) throw new RuntimeException("Cannot resolve " + what);
        return found;
    }

    static CtField uniqueField(String what, CtClass cls, Predicate<CtField> p) {
        CtField found = null;
        for (CtField f : cls.getDeclaredFields()) {
            if (!p.test(f)) continue;
            if (found != null) {
                throw new RuntimeException("Ambiguous " + what + " in " + cls.getName()
                    + ": " + found.getName() + " / " + f.getName());
            }
            found = f;
        }
        if (found == null) throw new RuntimeException("Cannot resolve " + what + " in " + cls.getName());
        return found;
    }

    static CtMethod uniqueMethod(String what, CtClass cls, Shaped p) {
        CtMethod found = uniqueOrNull(cls, p);
        if (found == null) throw new RuntimeException("Cannot resolve " + what + " in " + cls.getName());
        return found;
    }

    static CtMethod uniqueOrNull(CtClass cls, Shaped p) {
        CtMethod found = null;
        for (CtMethod m : cls.getDeclaredMethods()) {
            try {
                if (!p.test(m)) continue;
            } catch (Exception e) {
                continue;
            }
            if (found != null) {
                throw new RuntimeException("Ambiguous shape in " + cls.getName()
                    + ": " + found.getName() + " / " + m.getName());
            }
            found = m;
        }
        return found;
    }

    interface Shaped {
        boolean test(CtMethod m) throws Exception;
    }

    static CtMethod method(CtClass cls, String name) {
        for (CtMethod m : cls.getDeclaredMethods()) {
            if (m.getName().equals(name)) return m;
        }
        return null;
    }

    static boolean declares(CtClass cls, String field) {
        for (CtField f : cls.getDeclaredFields()) {
            if (f.getName().equals(field)) return true;
        }
        return false;
    }

    static void declared(CtClass cls, String field) {
        if (!declares(cls, field)) {
            throw new RuntimeException("Cannot resolve " + field + " in " + cls.getName());
        }
    }

    static void declared(List<CtClass> chain, String field, String what) {
        for (CtClass c : chain) {
            if (declares(c, field)) return;
        }
        throw new RuntimeException("Cannot resolve " + field + " anywhere above " + what);
    }

    static final String SWEEP_MESSAGE = "Dismissing left-open window";

    static String sweepExemption(ClassPool pool, String pcodeJar) throws Exception {
        for (String name : classesUnder(pcodeJar, "com.threerings.projectx.client.")) {
            CtClass c;
            try {
                c = pool.get(name);
            } catch (NotFoundException e) {
                continue;
            }
            ConstPool cp = c.getClassFile().getConstPool();
            int said = -1;
            for (int i = 1; i < cp.getSize(); i++) {
                if (cp.getTag(i) == ConstPool.CONST_String
                        && SWEEP_MESSAGE.equals(cp.getStringInfo(i))) {
                    said = i;
                    break;
                }
            }
            if (said < 0) continue;
            for (CtMethod m : c.getDeclaredMethods()) {
                CodeAttribute code = m.getMethodInfo().getCodeAttribute();
                if (code == null) continue;
                boolean prints = false;
                Set<String> tested = new LinkedHashSet<String>();
                CodeIterator it = code.iterator();
                while (it.hasNext()) {
                    int at = it.next();
                    int op = it.byteAt(at);
                    if (op == Opcode.LDC && it.byteAt(at + 1) == said) prints = true;
                    if (op == Opcode.LDC_W && it.u16bitAt(at + 1) == said) prints = true;
                    if (op == Opcode.INSTANCEOF) tested.add(cp.getClassInfo(it.u16bitAt(at + 1)));
                }
                if (!prints) continue;
                if (tested.size() != 1) {
                    throw new RuntimeException("Expected one window test beside \""
                        + SWEEP_MESSAGE + "\" in " + name + ", found " + tested);
                }
                String marker = tested.iterator().next();
                CtClass found = pool.get(marker);
                if (!found.isInterface() || found.getDeclaredMethods().length != 0) {
                    throw new RuntimeException("Expected a bare marker interface in " + name
                        + " beside \"" + SWEEP_MESSAGE + "\", found " + marker);
                }
                return marker;
            }
        }
        throw new RuntimeException("Cannot find what prints \"" + SWEEP_MESSAGE + "\"");
    }

    static String fieldWrittenBy(CtMethod m) {
        CodeAttribute code = m.getMethodInfo().getCodeAttribute();
        if (code == null) return null;
        try {
            String found = null;
            CodeIterator it = code.iterator();
            while (it.hasNext()) {
                int at = it.next();
                if (it.byteAt(at) != Opcode.PUTFIELD) continue;
                if (found != null) return null;
                found = m.getMethodInfo().getConstPool().getFieldrefName(it.u16bitAt(at + 1));
            }
            return found;
        } catch (BadBytecode e) {
            return null;
        }
    }

    static boolean plainFieldRead(CtMethod m) {
        CodeAttribute code = m.getMethodInfo().getCodeAttribute();
        if (code == null) return false;
        try {
            boolean field = false;
            int steps = 0;
            CodeIterator it = code.iterator();
            while (it.hasNext()) {
                int op = it.byteAt(it.next());
                if (op == Opcode.GETFIELD) field = true;
                steps++;
            }
            return field && steps <= 3;
        } catch (BadBytecode e) {
            return false;
        }
    }

    static List<String> classesUnder(String jar, String prefix) throws IOException {
        List<String> out = new ArrayList<String>();
        JarFile jf = new JarFile(jar);
        try {
            for (Enumeration<JarEntry> e = jf.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (!n.endsWith(".class")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                if (cn.startsWith(prefix)) out.add(cn);
            }
        } finally {
            jf.close();
        }
        Collections.sort(out);
        return out;
    }
}
