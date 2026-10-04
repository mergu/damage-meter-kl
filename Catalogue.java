import com.threerings.config.ArgumentMap;
import com.threerings.config.ConfigReference;
import com.threerings.config.ManagedConfig;
import com.threerings.export.BinaryImporter;
import com.threerings.tudey.config.ActorConfig;

import java.io.FileInputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Catalogue {
    static final String DERIVED_REF = "actor";

    static Map<String, ActorConfig> byName = new HashMap<String, ActorConfig>();
    static Field name;
    static Field derived;

    public static void main(String[] args) throws Exception {
        name = ManagedConfig.class.getDeclaredField("_name");
        name.setAccessible(true);
        derived = Class.forName("com.threerings.tudey.config.ActorConfig$Derived")
            .getField(DERIVED_REF);

        BinaryImporter in = new BinaryImporter(new FileInputStream(args[0]));
        ActorConfig[] all = (ActorConfig[]) in.qf();
        in.close();
        for (ActorConfig c : all) {
            byName.put((String) name.get(c), c);
        }

        List<String> names = new ArrayList<String>(byName.keySet());
        Collections.sort(names);
        PrintWriter out = new PrintWriter(args[1]);
        out.println("# every actor that can be hit, and what it leaves behind");
        out.println("# regenerate with ./catalogue.sh");
        out.println("#");
        out.println("# loot\timpl\thealth\tname");
        int listed = 0;
        for (String n : names) {
            Object impl = base(n, 0);
            if (impl == null) continue;
            Object health = field(impl, "health");
            if (health == null) continue;
            out.println(loot(n, 0) + "\t" + impl.getClass().getSimpleName() + "\t" + health
                + "\t" + n);
            listed++;
        }
        out.close();
        System.out.println("catalogued " + listed + " of " + all.length + " actors");
    }

    static Object base(String n, int depth) throws Exception {
        if (n == null || depth > 8) return null;
        ActorConfig c = byName.get(n);
        if (c == null || c.implementation == null) return null;
        if (!c.implementation.getClass().getSimpleName().equals("Derived")) {
            return c.implementation;
        }
        ConfigReference<?> ref = (ConfigReference<?>) derived.get(c.implementation);
        return ref == null ? null : base(ref.getName(), depth + 1);
    }

    static String loot(String n, int depth) throws Exception {
        if (n == null || depth > 8) return "-";
        ActorConfig c = byName.get(n);
        if (c == null || c.implementation == null) return "-";
        if (!c.implementation.getClass().getSimpleName().equals("Derived")) return "-";
        ConfigReference<?> ref = (ConfigReference<?>) derived.get(c.implementation);
        if (ref == null) return "-";
        ArgumentMap args = ref.kX();
        if (args != null) {
            Object said = args.get("Loot");
            if (said != null) return String.valueOf(said);
        }
        return loot(ref.getName(), depth + 1);
    }

    static Object field(Object on, String called) {
        for (Class<?> at = on.getClass(); at != null; at = at.getSuperclass()) {
            try {
                return at.getField(called).get(on);
            } catch (Exception e) {
            }
        }
        return null;
    }
}
