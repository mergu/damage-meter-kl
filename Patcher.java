import javassist.ClassPool;
import javassist.CtClass;
import javassist.CtField;
import javassist.CtNewConstructor;
import javassist.CtNewMethod;

public class Patcher {
    static final String PKG = "com.threerings.projectx.damagemeter.client";
    static final String MAIN = PKG + ".DamageMeter";
    static final String TALLY = PKG + ".Tally";
    static final String BAR = PKG + ".Bar";
    static final String ROW = PKG + ".Row";
    static final String METER = PKG + ".Panel";
    static final String WATCH = PKG + ".Watch";
    static final String MOUSE = PKG + ".Mouse";
    static final String RUN = PKG + ".Run";

    static final String TALLY_N = "Tally";
    static final String BAR_N = "Bar";
    static final String ROW_N = "Row";
    static final String METER_N = "Panel";
    static final String WATCH_N = "Watch";
    static final String MOUSE_N = "Mouse";
    static final String RUN_N = "Run";

    static final String OUT_DIR = "out";
    static final String PCODE = "projectx-pcode.jar";

    static final int WIDTH = 320;
    static final int LINE = 22;
    static final int PAD = 6;
    static final int SEATS = 8;
    static final int DEBUG_SLOTS = 40;
    static final int KNOWN_CAP = 1000;
    static final String LEDGER_DIR = ".sk-damage-meter";
    static final int DEBUG_PAGES = 5;
    static final int DEBUG_VALUE = 160;
    static final int DEBUG_CHARS = 44;
    static final String DEBUG_INDENT = "      ";

    static final int ICON = LINE - 2;
    // the reset and hide buttons sit in the header's right end, one cell each
    static final int BOX = LINE;
    // what's left showing when the meter is hidden
    static final int TAB = BOX + PAD * 2;
    static final String QUIET = "90000L";
    static final String HANDOVER = "4000L";
    static final int MUCH_MIN = 48;
    static final int SHARE_MIN = 40;
    static final int RANK_W = 16;
    static final int TEXT_PAD = 6;
    static final int SLACK = 4;
    static final String BUFFER = " ";
    static final int RIGHTWARD = 1;
    static final int COL_GAP = 8;
    static final int TEXT_LIFT = 2;
    static final boolean CHOSEN_COLOURS = false;
    static final String PALETTE = "210,28,140,45,270,178,320,0,95,240";
    static final String BAR_EDGE = "0.34";
    static final String BAR_TOP = "1.5";
    static final String BAR_FOOT = "0.72";
    static final String GONE_MARK = "~";
    static final String TEMPLATES = "/Parts/,(Dev),Dev/,ModelTester";
    static final String PICKED = picks();
    // the head's removal reaches us a few hundred ms after the zombie it turns into
    static final int REVIVE_BEFORE = 600;
    // totem pulse radius is 6
    static final String TOTEM_REACH = "7.0";
    static final String GRIM_TOTEM = "Dynamic/Monster Objects/Grim Totem";
    static final String DEBUG_TINT = "1.0f, 0.25f, 0.25f";
    static final String ZOMBIE_FAMILY = "/Undead/Zombie/";

    static String picks() {
        StringBuilder out = new StringBuilder();
        java.io.File from = new java.io.File("targets.txt");
        if (!from.exists()) {
            System.out.println("no targets.txt: going by what the game says each thing leaves");
            return "";
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(from.toPath())) {
                String rule = line.trim();
                if (rule.length() < 2) continue;
                char how = rule.charAt(0);
                if (how != '+' && how != '-') continue;
                String what = rule.substring(1).trim();
                if (what.isEmpty() || what.indexOf('|') >= 0 || what.indexOf('^') >= 0) continue;
                String where = "";
                int open = what.lastIndexOf('[');
                if (open >= 0 && what.endsWith("]")) {
                    for (String id : what.substring(open + 1, what.length() - 1).split(",")) {
                        if (id.trim().isEmpty()) continue;
                        where = where + (where.isEmpty() ? "" : ",") + id.trim();
                    }
                    what = what.substring(0, open).trim();
                    if (what.isEmpty() || where.isEmpty()) {
                        throw new RuntimeException("targets.txt: nothing to go on in " + rule);
                    }
                }
                if (out.length() > 0) out.append('|');
                out.append(how).append(what);
                if (!where.isEmpty()) out.append('^').append(where);
            }
        } catch (java.io.IOException e) {
            throw new RuntimeException("cannot read targets.txt", e);
        }
        System.out.println("picked out " + (out.length() == 0 ? 0
            : out.toString().split("\\|").length) + " rules from targets.txt");
        return out.toString();
    }

    static final String INDEX_SLICE = "400";
    // no single hit counts above this: the game's fixed "kill it" damage is 99,999 and up
    // (railgun auto-turrets do 999,999), and real hits stay in the thousands
    static final long MAX_HIT = 10000L;
    static final int KEPT_RUNS = 6;

    public static void main(String[] args) throws Exception {
        ClassPool pool = new ClassPool(true);
        pool.appendClassPath(PCODE);
        Discovery.Discovered d = Discovery.discover(pool, PCODE);

        CtClass tallyCt = pool.makeClass(TALLY);
        CtClass runCt = pool.makeClass(RUN);
        CtClass mainCt = pool.makeClass(MAIN);
        CtClass barCt = pool.makeClass(BAR, pool.get(d.backgroundBaseClass));
        CtClass rowCt = pool.makeClass(ROW, pool.get(Discovery.CONTAINER));
        CtClass meterCt = pool.makeClass(METER, pool.get(d.windowClass));
        // keeps the game from closing the meter on every place change
        meterCt.setInterfaces(new CtClass[]{pool.get(d.keptWindowIface)});
        CtClass watchCt = pool.makeClass(WATCH);
        watchCt.setInterfaces(new CtClass[]{pool.get(d.damageIface),
            pool.get(d.tickableIface)});
        CtClass mouseCt = pool.makeClass(MOUSE, pool.get(d.mouseAdapterClass));

        fillTally(tallyCt);
        fillRun(runCt);
        fillBar(d, barCt);
        fillMain(d, mainCt);
        fillRow(d, rowCt);
        meterFields(d, meterCt);
        fillLedger(d, mainCt);
        meterMethods(d, meterCt);
        fillMouse(d, mouseCt);
        meterCtor(d, meterCt);
        watchFields(watchCt);
        fillWindowGlue(d, mainCt);
        fillWatch(d, watchCt);

        CtClass dirCt = pool.get(d.dirBaseClass);
        hookDirector(d, dirCt);

        CtClass[] toWrite = {tallyCt, runCt, barCt, mainCt, rowCt, mouseCt, meterCt, watchCt,
            dirCt};
        for (CtClass c : toWrite) {
            c.writeFile(OUT_DIR);
        }
    }

    private static void fillTally(CtClass c) throws Exception {
        c.addField(CtField.make(
            "public java.util.HashMap recent = new java.util.HashMap();", c));
        c.addField(CtField.make(
            "public java.util.HashMap held = new java.util.HashMap();", c));
        for (String book : new String[]{"dealt", "taken", "deaths", "first", "last"}) {
            c.addField(CtField.make(
                "public java.util.HashMap " + book + " = new java.util.HashMap();", c));
        }
        c.addConstructor(CtNewConstructor.make("public " + TALLY_N + "() { }", c));

        c.addMethod(CtNewMethod.make(
            "public java.util.HashMap book(int kind) {" +
            "  if (kind == 1) { return this.taken; }" +
            "  if (kind == 2) { return this.deaths; }" +
            "  return this.dealt;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public boolean isEmpty() {" +
            "  return this.dealt.isEmpty() && this.taken.isEmpty() && this.deaths.isEmpty();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public long get(int kind, String who) {" +
            "  Object had = this.book(kind).get(who);" +
            "  return had == null ? 0L : ((Long) had).longValue();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void add(int kind, String who, long amount, long when) {" +
            "  String key = who;" +
            "  java.util.HashMap b = this.book(kind);" +
            "  Object had = b.get(key);" +
            "  b.put(key, new Long((had == null ? 0L : ((Long) had).longValue()) + amount));" +
            "  if (!this.first.containsKey(key)) { this.first.put(key, new Long(when)); }" +
            "  this.last.put(key, new Long(when));" +
            "  if (kind != 0) { return; }" +
            "  java.util.ArrayList log = (java.util.ArrayList) this.recent.get(key);" +
            "  if (log == null) { log = new java.util.ArrayList(); this.recent.put(key, log); }" +
            "  long[] mark = new long[2];" +
            "  mark[0] = when;" +
            "  mark[1] = amount;" +
            "  log.add(mark);" +
            "  while (!log.isEmpty() && ((long[]) log.get(0))[0] < when - 30000L) { log.remove(0); }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public float seconds(String who) {" +
            "  String key = who;" +
            "  Object from = this.first.get(key);" +
            "  Object to = this.last.get(key);" +
            "  if (from == null || to == null) { return 1.0f; }" +
            "  float span = ((float) (((Long) to).longValue() - ((Long) from).longValue())) * 0.001f;" +
            "  return span < 1.0f ? 1.0f : span;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public long rate(String who, long now, long window) {" +
            "  java.util.ArrayList log = (java.util.ArrayList) this.recent.get(who);" +
            "  if (log == null || log.isEmpty()) { return 0L; }" +
            "  long since = now - window;" +
            "  long sum = 0L;" +
            "  int i = 0;" +
            "  while (i < log.size()) {" +
            "    long[] mark = (long[]) log.get(i);" +
            "    i = i + 1;" +
            "    if (mark[0] >= since) { sum = sum + mark[1]; }" +
            "  }" +
            "  return (sum * 1000L) / window;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void hold(String who, long value) {" +
            "  this.held.put(who, new Long(value));" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public long heldRate(String who) {" +
            "  Object had = this.held.get(who);" +
            "  return had == null ? 0L : ((Long) had).longValue();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public long total(int kind) {" +
            "  long sum = 0L;" +
            "  java.util.Iterator it = this.book(kind).values().iterator();" +
            "  while (it.hasNext()) { sum = sum + ((Long) it.next()).longValue(); }" +
            "  return sum;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public java.util.ArrayList who(int kind) {" +
            "  return new java.util.ArrayList(this.book(kind).keySet());" +
            "}", c));

        c.addField(CtField.make(
            "public static String[] BOOKS = \"dealt,taken,deaths,first,last\"" +
            ".split(\",\");", c));

        c.addMethod(CtNewMethod.make(
            "public java.util.HashMap named(String name) {" +
            "  if (\"taken\".equals(name)) { return this.taken; }" +
            "  if (\"deaths\".equals(name)) { return this.deaths; }" +
            "  if (\"first\".equals(name)) { return this.first; }" +
            "  if (\"last\".equals(name)) { return this.last; }" +
            "  return this.dealt;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void save(java.util.Properties store, String prefix) {" +
            "  int b = 0;" +
            "  while (b < BOOKS.length) {" +
            "    String name = BOOKS[b];" +
            "    b = b + 1;" +
            "    StringBuffer out = new StringBuffer();" +
            "    java.util.Iterator it = this.named(name).entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      if (out.length() > 0) { out.append('|'); }" +
            "      out.append((String) e.getKey()).append('=').append(e.getValue());" +
            "    }" +
            "    store.setProperty(prefix + \".\" + name, out.toString());" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void load(java.util.Properties store, String prefix) {" +
            "  int b = 0;" +
            "  while (b < BOOKS.length) {" +
            "    String name = BOOKS[b];" +
            "    b = b + 1;" +
            "    java.util.HashMap into = this.named(name);" +
            "    into.clear();" +
            "    String line = store.getProperty(prefix + \".\" + name);" +
            "    if (line == null || line.length() == 0) { continue; }" +
            "    String[] parts = line.split(\"[|]\");" +
            "    int i = 0;" +
            "    while (i < parts.length) {" +
            "      String part = parts[i];" +
            "      i = i + 1;" +
            "      int at = part.indexOf('=');" +
            "      if (at <= 0) { continue; }" +
            "      try {" +
            "        into.put(part.substring(0, at)," +
            "            new Long(Long.parseLong(part.substring(at + 1))));" +
            "      } catch (Throwable t) { }" +
            "    }" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void clear() {" +
            "  this.dealt.clear(); this.taken.clear(); this.deaths.clear();" +
            "  this.first.clear(); this.last.clear();" +
            "  this.recent.clear();" +
            "  this.held.clear();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void soak(" + TALLY + " other) {" +
            "  int kind = 0;" +
            "  while (kind < 3) {" +
            "    java.util.Iterator it = other.book(kind).entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      Object had = this.book(kind).get(e.getKey());" +
            "      long was = had == null ? 0L : ((Long) had).longValue();" +
            "      this.book(kind).put(e.getKey()," +
            "          new Long(was + ((Long) e.getValue()).longValue()));" +
            "    }" +
            "    kind = kind + 1;" +
            "  }" +
            "  this.first.putAll(other.first);" +
            "  this.last.putAll(other.last);" +
            "}", c));
    }

    private static void fillBar(Discovery.Discovered d, CtClass c) throws Exception {
        c.addField(CtField.make("public " + d.imageClass + " art = null;", c));
        c.addField(CtField.make("public " + Discovery.COLOR4F + " tint = null;", c));
        c.addField(CtField.make("public " + Discovery.COLOR4F + " track = null;", c));
        c.addField(CtField.make("public float frac = 0.0f;", c));

        c.addConstructor(CtNewConstructor.make(
            "public " + BAR_N + "(" + d.imageClass + " art, " + Discovery.COLOR4F + " tint," +
            " " + Discovery.COLOR4F + " track) {" +
            "  this.art = art;" +
            "  this.tint = tint;" +
            "  this.track = track;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void quad(" + Discovery.RENDERER + " r, int x, int y, int w, int h," +
            " " + Discovery.COLOR4F + " colour, float alpha) {" +
            "  if (this.art == null || colour == null || w <= 0 || h <= 0) { return; }" +
            "  this.art.render(r, 0, 0, this.art.getWidth(), this.art.getHeight()," +
            "      x, y, w, h, colour, alpha);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public " + Discovery.COLOR4F + " step(" + Discovery.COLOR4F + " c, float by) {" +
            "  if (c == null) { return null; }" +
            "  float rr = c.r * by;" +
            "  float gg = c.g * by;" +
            "  float bb = c.b * by;" +
            "  if (rr > 1.0f) { rr = 1.0f; }" +
            "  if (gg > 1.0f) { gg = 1.0f; }" +
            "  if (bb > 1.0f) { bb = 1.0f; }" +
            "  return new " + Discovery.COLOR4F + "(rr, gg, bb, c.a);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void render(" + Discovery.RENDERER + " r, int x, int y, int w, int h," +
            " float alpha) {" +
            "  int tall = h - 2;" +
            "  if (tall <= 0) { return; }" +
            "  this.quad(r, x, y + 1, w, tall, this.track, alpha);" +
            "  int lit = (int) (((float) w) * this.frac);" +
            "  if (lit <= 0 || this.tint == null) { return; }" +
            "  this.quad(r, x, y + 1, lit, tall, this.step(this.tint, " + BAR_EDGE + "f), alpha);" +
            "  if (lit <= 2 || tall <= 2) { return; }" +
            "  this.quad(r, x + 1, y + 2, lit - 2, tall - 2, this.tint, alpha);" +
            "  this.quad(r, x + 1, y + tall - 1, lit - 2, 1," +
            "      this.step(this.tint, " + BAR_TOP + "f), alpha);" +
            "  this.quad(r, x + 1, y + 2, lit - 2, 1," +
            "      this.step(this.tint, " + BAR_FOOT + "f), alpha);" +
            "}", c));
    }

    private static void fillMain(Discovery.Discovered d, CtClass c) throws Exception {
        c.addField(CtField.make("public static Object ctx = null;", c));
        c.addField(CtField.make("public static Object party = null;", c));
        c.addField(CtField.make("public static Object boss = null;", c));
        c.addField(CtField.make("public static float away = 0.0f;", c));
        c.addField(CtField.make("public static long stirred = 0L;", c));
        c.addField(CtField.make("public static Object watch = null;", c));
        c.addField(CtField.make("public static Object pacer = null;", c));
        c.addField(CtField.make("public static Object meter = null;", c));
        // weak, or the game logs that old scene views were never released
        c.addField(CtField.make("public static java.lang.ref.WeakReference seeing = null;", c));
        c.addField(CtField.make("public static boolean looked = false;", c));
        c.addField(CtField.make("public static java.lang.ref.WeakReference seenIn = null;", c));
        c.addField(CtField.make("public static long lookedAt = 0L;", c));
        c.addField(CtField.make("public static java.util.HashMap lootBook = new java.util.HashMap();", c));
        c.addField(CtField.make("public static String seenPath = null;", c));
        c.addField(CtField.make("public static Object seenRef = null;", c));
        c.addField(CtField.make("public static Object seenActor = null;", c));
        c.addField(CtField.make("public static String seenRule = null;", c));
        c.addField(CtField.make("public static java.util.HashMap lastHits = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.util.ArrayList goneHeads = new java.util.ArrayList();", c));
        c.addField(CtField.make("public static java.lang.ref.WeakReference headsIn = null;", c));
        c.addField(CtField.make("public static java.util.HashSet scanned = new java.util.HashSet();", c));
        c.addField(CtField.make("public static boolean memoDirty = false;", c));
        c.addField(CtField.make("public static java.lang.ref.WeakReference scannedIn = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fCreated = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fModel = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fRecords = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fStamp = null;", c));
        // zombie heads in sight, id -> sprite, so we notice when one goes
        c.addField(CtField.make("public static java.util.HashMap heads = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.lang.ref.WeakReference headsSeenIn = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fSprites = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fAmount = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fVictim = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fSource = null;", c));
        // id@created, so marks survive the ready room but not an id reused on another floor
        c.addField(CtField.make("public static java.util.LinkedHashSet revived = new java.util.LinkedHashSet();", c));
        c.addField(CtField.make("public static java.lang.ref.WeakReference swept = null;", c));
        c.addField(CtField.make("public static java.util.HashMap painted = new java.util.HashMap();", c));
        c.addField(CtField.make("public static Object sharedWhite = null;", c));
        c.addField(CtField.make("public static boolean wantRevived = "
            + (PICKED.contains("@Revived") ? "true" : "false") + ";", c));
        c.addField(CtField.make("public static boolean debug = false;", c));
        c.addField(CtField.make("public static int debugPage = 0;", c));
        c.addField(CtField.make("public static String[] rules = "
            + (PICKED.isEmpty() ? "new String[0];" : "\"" + PICKED + "\".split(\"[|]\");"), c));

        c.addField(CtField.make("public static int colRank = 0;", c));
        c.addField(CtField.make("public static int colWho = 0;", c));
        c.addField(CtField.make("public static int colMuch = 0;", c));
        c.addField(CtField.make("public static int colShare = 0;", c));

        c.addField(CtField.make("public static java.util.HashMap runs = new java.util.HashMap();", c));
        c.addField(CtField.make("public static " + RUN + " current = new " + RUN + "();", c));
        c.addField(CtField.make("public static " + TALLY + " floorBook = current.floor;", c));
        c.addField(CtField.make("public static " + TALLY + " lastBook = current.last;", c));
        c.addField(CtField.make("public static " + TALLY + " runBook = current.run;", c));
        c.addField(CtField.make("public static int mode = 0;", c));
        c.addField(CtField.make("public static int segment = 0;", c));
        c.addField(CtField.make("public static int depthSeen = -1;", c));
        c.addField(CtField.make("public static java.util.HashMap fields = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.util.HashMap slots = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.util.HashMap badges = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.util.HashMap lastSeen = new java.util.HashMap();", c));
        c.addField(CtField.make("public static " + Discovery.COLOR4F + " GONE_INK ="
            + " new " + Discovery.COLOR4F + "(0.55f, 0.56f, 0.60f, 1.0f);", c));
        c.addField(CtField.make("public static Object NONE = new Object();", c));
        c.addField(CtField.make("public static java.util.HashMap tints = new java.util.HashMap();", c));
        c.addField(CtField.make("public static long strayed = 0L;", c));
        c.addField(CtField.make("public static int dropped = 0;", c));
        c.addField(CtField.make("public static String droppedNote = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fWindows = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Field fHover = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Method mHover = null;", c));
        c.addField(CtField.make("public static java.lang.reflect.Method mWindowOf = null;", c));
        c.addField(CtField.make("public static long LIVE = 10000L;", c));
        c.addField(CtField.make("public static java.util.HashMap standing = new java.util.HashMap();", c));
        c.addField(CtField.make("public static boolean loaded = false;", c));
        c.addField(CtField.make("public static long parted = 0L;", c));
        c.addField(CtField.make("public static int taken_in = 0;", c));
        c.addField(CtField.make("public static int written = 0;", c));
        c.addField(CtField.make("public static boolean hidden = false;", c));

        c.addMethod(CtNewMethod.make(
            "public static Object findView(Object from, int depth) {" +
            "  try {" +
            "    if (from == null || depth > 2) { return null; }" +
            "    if (from instanceof " + d.sceneViewClass + ") { return from; }" +
            "    Class at = from.getClass();" +
            "    while (at != null && at != Object.class) {" +
            "      java.lang.reflect.Field[] fs = at.getDeclaredFields();" +
            "      int i = 0;" +
            "      while (i < fs.length) {" +
            "        java.lang.reflect.Field f = fs[i];" +
            "        i = i + 1;" +
            "        if (java.lang.reflect.Modifier.isStatic(f.getModifiers())" +
            "            || f.getType().isPrimitive()) { continue; }" +
            "        f.setAccessible(true);" +
            "        Object found = findView(f.get(from), depth + 1);" +
            "        if (found != null) { return found; }" +
            "      }" +
            "      at = at.getSuperclass();" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object place() {" +
            "  try {" +
            "    if (ctx == null) { return null; }" +
            "    Object dir = ((" + d.crowdCtxIface + ") ctx)." + d.locationDirMethod + "();" +
            "    if (dir == null) { return null; }" +
            "    return ((" + d.locationDirClass + ") dir)." + d.placeObjectMethod + "();" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        // the party moves to the next floor before we do, so recheck the place every time
        c.addMethod(CtNewMethod.make(
            "public static Object seen() {" +
            "  Object here = place();" +
            "  Object was = seenIn == null ? null : seenIn.get();" +
            "  if (here != was) {" +
            "    seenIn = here == null ? null : new java.lang.ref.WeakReference(here);" +
            "    seeing = null;" +
            "    looked = false;" +
            "  }" +
            "  Object view = seeing == null ? null : seeing.get();" +
            "  if (view != null) { return view; }" +
            "  long now = System.currentTimeMillis();" +
            "  if (looked && now - lookedAt < 1000L) { return null; }" +
            "  looked = true;" +
            "  lookedAt = now;" +
            "  try {" +
            "    Object dir = ((" + d.crowdCtxIface + ") ctx)." + d.locationDirMethod + "();" +
            "    view = findView(dir, 0);" +
            "    seeing = view == null ? null : new java.lang.ref.WeakReference(view);" +
            "  } catch (Throwable t) { }" +
            "  return view;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String lootOf(String name) {" +
            "  Object had = lootBook.get(name);" +
            "  if (had != null) { return (String) had; }" +
            "  String found = \"\";" +
            "  try {" +
            "    Object cfgmgr = ((" + d.glCtxClass + ") ctx).getConfigManager();" +
            "    String at = name;" +
            "    int steps = 0;" +
            "    while (at != null && steps < 8) {" +
            "      steps = steps + 1;" +
            "      Object cfg = ((" + Discovery.CONFIG_MANAGER + ") cfgmgr)." + d.cfgByNameMethod +
            "          (" + Discovery.ACTOR_CONFIG + ".class, at);" +
            "      if (cfg == null) { break; }" +
            "      Object impl = ((" + Discovery.ACTOR_CONFIG + ") cfg).implementation;" +
            "      if (!(impl instanceof " + Discovery.ACTOR_DERIVED + ")) { break; }" +
            "      Object ref = ((" + Discovery.ACTOR_DERIVED + ") impl).actor;" +
            "      if (ref == null) { break; }" +
            "      Object args = ((" + Discovery.CONFIG_REF + ") ref)." + d.refArgsMethod + "();" +
            "      Object said = args == null ? null" +
            "          : ((java.util.Map) args).get(\"" + Discovery.LOOT_ARG + "\");" +
            "      if (said != null) { found = String.valueOf(said); break; }" +
            "      at = ((" + Discovery.CONFIG_REF + ") ref).getName();" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  lootBook.put(name, found);" +
            "  return found;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String leaves(Object ref, String path) {" +
            "  if (ref == null) { return lootOf(path); }" +
            "  Object args = ((" + Discovery.CONFIG_REF + ") ref)." + d.refArgsMethod + "();" +
            "  Object said = args == null ? null" +
            "      : ((java.util.Map) args).get(\"" + Discovery.LOOT_ARG + "\");" +
            "  return said != null ? String.valueOf(said) : lootOf(path);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String markOf(Object object) {" +
            "  try {" +
            "    if (object instanceof " + Discovery.MISSION_PARTY + ") {" +
            "      String id = ((" + Discovery.MISSION_PARTY + ") object).missionId;" +
            "      if (id != null && id.length() > 0) { return \"mission \" + id; }" +
            "    }" +
            "    if (object instanceof " + Discovery.LEVEL_PARTY + ") {" +
            "      " + Discovery.LEVEL_PARTY + " level = (" + Discovery.LEVEL_PARTY + ") object;" +
            "      return \"gate \" + level.actualGateId + \" \" + String.valueOf(level.difficulty);" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return \"\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.lang.reflect.Field grab(Class owner, String name) {" +
            "  java.util.HashMap known = (java.util.HashMap) fields.get(owner);" +
            "  if (known == null) { known = new java.util.HashMap(); fields.put(owner, known); }" +
            "  Object had = known.get(name);" +
            "  if (had != null) { return had == NONE ? null : (java.lang.reflect.Field) had; }" +
            "  Class at = owner;" +
            "  while (at != null) {" +
            "    try {" +
            "      java.lang.reflect.Field f = at.getDeclaredField(name);" +
            "      f.setAccessible(true);" +
            "      known.put(name, f);" +
            "      return f;" +
            "    } catch (Throwable t) { at = at.getSuperclass(); }" +
            "  }" +
            "  known.put(name, NONE);" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int made(Object actor) {" +
            "  try {" +
            "    if (fCreated == null) { fCreated = grab(actor.getClass(), \"_created\"); }" +
            "    return fCreated.getInt(actor);" +
            "  } catch (Throwable t) { return 0; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.util.Map spritesOf(Object view) {" +
            "  try {" +
            "    if (fSprites == null) { fSprites = grab(view.getClass(), \"" + d.spriteMapField + "\"); }" +
            "    return (java.util.Map) fSprites.get(view);" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        // the timestamp of the last scene update applied, which is what the view hands
        // to the sprites it removes in that update
        c.addMethod(CtNewMethod.make(
            "public static int lastStamp(Object view) {" +
            "  try {" +
            "    if (fRecords == null) { fRecords = grab(view.getClass(), \"_records\"); }" +
            "    java.util.List kept = (java.util.List) fRecords.get(view);" +
            "    if (kept == null || kept.isEmpty()) { return 0; }" +
            "    Object last = kept.get(kept.size() - 1);" +
            "    if (fStamp == null) { fStamp = grab(last.getClass(), \"_timestamp\"); }" +
            "    return fStamp.getInt(last);" +
            "  } catch (Throwable t) { return 0; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object modelOf(Object sprite) {" +
            "  try {" +
            "    if (fModel == null) { fModel = grab(sprite.getClass(), \"_model\"); }" +
            "    return fModel.get(sprite);" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object peekAt(Object on, String name) {" +
            "  try {" +
            "    java.lang.reflect.Field f = grab(on.getClass(), name);" +
            "    return f == null ? null : f.get(on);" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int peek(Object on, String name) {" +
            "  try {" +
            "    java.lang.reflect.Field f = grab(on.getClass(), name);" +
            "    return f == null ? 0 : f.getInt(on);" +
            "  } catch (Throwable t) { return 0; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String memoKey(Object view, int id) {" +
            "  Object model = ((" + d.sceneViewClass + ") view)." + d.sceneModelMethod + "();" +
            "  int scene = model == null ? 0 : ((" + Discovery.WHIRLED_MODEL + ") model).sceneId;" +
            "  return scene + \":\" + id;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String raisedKey(int id, int made) { return id + \"@\" + made; }", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean raised(int id, Object actor) {" +
            "  return actor != null && revived.contains(raisedKey(id, made(actor)));" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String verdict(int victim, Object actor, Object ref, String path, Object view) {" +
            "  String kind = null;" +
            "  String mission = null;" +
            "  int r = 0;" +
            "  while (r < rules.length) {" +
            "    String rule = rules[r];" +
            "    r = r + 1;" +
            "    if (rule.length() < 2) { continue; }" +
            "    String what = rule.substring(1);" +
            "    int only = what.indexOf('^');" +
            "    if (only >= 0) {" +
            "      if (mission == null) {" +
            "        String mark = party != null ? markOf(party) : current.mark;" +
            "        mission = mark.startsWith(\"mission \") ? mark.substring(8) : \"\";" +
            "      }" +
            "      String places = \",\" + what.substring(only + 1) + \",\";" +
            "      if (mission.length() == 0" +
            "          || places.indexOf(\",\" + mission + \",\") < 0) { continue; }" +
            "      what = what.substring(0, only);" +
            "    }" +
            "    boolean hit;" +
            "    if (what.equals(\"@Revived\")) {" +
            "      hit = raised(victim, actor)" +
            "          || view != null && current.recallRaised(memoKey(view, victim));" +
            "    } else if (what.charAt(0) == '@') {" +
            "      if (kind == null) { kind = leaves(ref, path); }" +
            "      hit = kind.equals(what.substring(1));" +
            "    } else {" +
            "      hit = path.indexOf(what) >= 0;" +
            "    }" +
            "    if (!hit) { continue; }" +
            "    return rule.charAt(0) == '+' ? null : rule;" +
            "  }" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean worthCounting(int victim) {" +
            "  seenPath = null;" +
            "  seenRef = null;" +
            "  seenActor = null;" +
            "  seenRule = null;" +
            "  try {" +
            "    if (victim == 0) { return true; }" +
            "    Object view = seen();" +
            "    if (view == null) { return true; }" +
            "    Object sprite = ((" + d.sceneViewClass + ") view)." + d.spriteByIdMethod +
                  "(victim);" +
            "    if (sprite == null) {" +
            "      String known = current.recall(memoKey(view, victim));" +
            "      if (known == null) { return true; }" +
            "      seenPath = known;" +
            "      String left = verdict(victim, null, null, known, view);" +
            "      if (left == null) { return true; }" +
            "      seenRule = left;" +
            "      return false;" +
            "    }" +
            "    Object actor = ((" + d.spriteClass + ") sprite)." + d.spriteActorMethod + "();" +
            "    if (actor == null) { return true; }" +
            "    Object ref = ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "    if (ref == null) { return true; }" +
            "    String path = ((" + Discovery.CONFIG_REF + ") ref).getName();" +
            "    if (path == null) { return true; }" +
            "    seenPath = path;" +
            "    seenRef = ref;" +
            "    seenActor = actor;" +
            "    String rule = verdict(victim, actor, ref, path, view);" +
            "    if (rule == null) { return true; }" +
            "    seenRule = rule;" +
            "    return false;" +
            "  } catch (Throwable t) { return true; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void markRevived(Object view, int id, int made, String path) {" +
            "  current.remember(memoKey(view, id), path, true);" +
            "  memoDirty = true;" +
            "  if (revived.add(raisedKey(id, made)) && revived.size() > 512) {" +
            "    java.util.Iterator it = revived.iterator();" +
            "    it.next();" +
            "    it.remove();" +
            "  }" +
            "}", c));

        // the zombie a totem raises shows up in the same scene update that removes its head,
        // or one shortly before
        c.addMethod(CtNewMethod.make(
            "public static void headGone(Object view, Object sprite, int gone) {" +
            "  if (!wantRevived && !debug) { return; }" +
            "  try {" +
            "    Object actor = ((" + d.spriteClass + ") sprite)." + d.spriteActorMethod + "();" +
            "    if (actor == null) { return; }" +
            "    Object ref = ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "    if (ref == null || !\"" + Discovery.ZOMBIE_HEAD + "\".equals(((" + Discovery.CONFIG_REF + ") ref).getName())) { return; }" +
            "    java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "    if (sprites == null) { return; }" +
            "    if (headsIn == null || headsIn.get() != view) {" +
            "      headsIn = new java.lang.ref.WeakReference(view);" +
            "      goneHeads.clear();" +
            "    }" +
            "    " + Discovery.VECTOR2F + " spot = ((" + Discovery.ACTOR + ") actor)." + d.actorWhereMethod + "();" +
            "    if (spot == null) { return; }" +
            "    int lasted = gone - made(actor);" +
            "    double near = 99999.0;" +
            "    int best = 0;" +
            "    int bestMade = 0;" +
            "    String bestName = null;" +
            "    double bestFit = -1.0;" +
            "    java.util.Iterator it = sprites.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      Object other = e.getValue() == null ? null" +
            "          : ((" + d.spriteClass + ") e.getValue())." + d.spriteActorMethod + "();" +
            "      if (other == null || other == actor) { continue; }" +
            "      Object oref = ((" + Discovery.ACTOR + ") other)." + d.actorRefMethod + "();" +
            "      String name = oref == null ? null : ((" + Discovery.CONFIG_REF + ") oref).getName();" +
            "      if (name == null) { continue; }" +
            "      " + Discovery.VECTOR2F + " at = ((" + Discovery.ACTOR + ") other)." + d.actorWhereMethod + "();" +
            "      if (at == null) { continue; }" +
            "      double dx = (double) (at.x - spot.x);" +
            "      double dy = (double) (at.y - spot.y);" +
            "      double dist = Math.sqrt(dx * dx + dy * dy);" +
            "      if (\"" + GRIM_TOTEM + "\".equals(name)) {" +
            "        if (dist < near) { near = dist; }" +
            "        continue;" +
            "      }" +
            "      if (!(other instanceof " + Discovery.MONSTER + ") || name.indexOf(\"" + ZOMBIE_FAMILY + "\") < 0) { continue; }" +
            "      int m = made(other);" +
            "      int after = m - gone;" +
            "      if (after < -" + REVIVE_BEFORE + " || after > 0 || m < gone - lasted) { continue; }" +
            "      int id = ((Integer) e.getKey()).intValue();" +
            "      if (revived.contains(raisedKey(id, m))) { continue; }" +
            "      double fit = Math.abs(after) + Math.min(dist, 999.0) / 1000.0;" +
            "      if (bestFit < 0.0 || fit < bestFit) { best = id; bestMade = m; bestFit = fit; bestName = name; }" +
            "    }" +
            "    double[] head = new double[] { (double) spot.x, (double) spot.y, (double) gone," +
            "        near <= " + TOTEM_REACH + " ? 0.0 : 2.0, (double) lasted, 0.0, near };" +
            "    if (head[3] == 0.0 && bestFit >= 0.0) {" +
            "      markRevived(view, best, bestMade, bestName);" +
            "      head[3] = 1.0;" +
            "      head[5] = (double) best;" +
            "    }" +
            "    goneHeads.add(head);" +
            "    if (goneHeads.size() > 64) { goneHeads.remove(0); }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        // every beat: note the zombie heads in sight
        c.addMethod(CtNewMethod.make(
            "public static void findHeads() {" +
            "  if (!wantRevived && !debug) { return; }" +
            "  try {" +
            "    Object view = seen();" +
            "    java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "    if (sprites == null) { return; }" +
            "    if (headsSeenIn == null || headsSeenIn.get() != view) {" +
            "      headsSeenIn = new java.lang.ref.WeakReference(view);" +
            "      heads.clear();" +
            "    }" +
            "    java.util.Iterator it = sprites.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      if (e.getValue() == null || heads.containsKey(e.getKey())) { continue; }" +
            "      Object actor = ((" + d.spriteClass + ") e.getValue())." + d.spriteActorMethod + "();" +
            "      Object ref = actor == null ? null : ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "      if (ref != null && \"" + Discovery.ZOMBIE_HEAD + "\".equals(((" + Discovery.CONFIG_REF + ") ref).getName())) {" +
            "        heads.put(e.getKey(), e.getValue());" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        // every frame: a known head whose sprite has left the view was just removed
        c.addMethod(CtNewMethod.make(
            "public static void watchHeads() {" +
            "  if (heads.isEmpty()) { return; }" +
            "  try {" +
            "    Object view = headsSeenIn == null ? null : headsSeenIn.get();" +
            "    java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "    if (sprites == null) { heads.clear(); return; }" +
            "    java.util.Iterator it = heads.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      if (sprites.get(e.getKey()) == e.getValue()) { continue; }" +
            "      it.remove();" +
            "      headGone(view, e.getValue(), lastStamp(view));" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        // hits arrive for actors out of range too, so remember what the rules left out
        c.addMethod(CtNewMethod.make(
            "public static void learn() {" +
            "  if (rules.length == 0) { return; }" +
            "  try {" +
            "    Object view = seen();" +
            "    java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "    if (sprites == null) { return; }" +
            "    if (scannedIn == null || scannedIn.get() != view) {" +
            "      scannedIn = new java.lang.ref.WeakReference(view);" +
            "      scanned.clear();" +
            "    }" +
            "    java.util.HashSet now = new java.util.HashSet();" +
            "    java.util.Iterator it = sprites.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      now.add(e.getKey());" +
            "      if (scanned.contains(e.getKey())) { continue; }" +
            "      Object actor = e.getValue() == null ? null" +
            "          : ((" + d.spriteClass + ") e.getValue())." + d.spriteActorMethod + "();" +
            "      if (actor == null) { continue; }" +
            "      Object ref = ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "      String path = ref == null ? null : ((" + Discovery.CONFIG_REF + ") ref).getName();" +
            "      if (path == null || path.indexOf('|') >= 0) { continue; }" +
            "      int id = ((Integer) e.getKey()).intValue();" +
            "      if (verdict(id, actor, ref, path, view) != null) {" +
            "        current.remember(memoKey(view, id), path, false);" +
            "        memoDirty = true;" +
            "      }" +
            "    }" +
            "    scanned = now;" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String headGap(Object actor, String path) {" +
            "  if (actor == null || path == null) { return \"not met\"; }" +
            "  " + Discovery.VECTOR2F + " at = ((" + Discovery.ACTOR + ") actor)." + d.actorWhereMethod + "();" +
            "  int m = made(actor);" +
            "  double[] best = null;" +
            "  int h = 0;" +
            "  while (h < goneHeads.size()) {" +
            "    double[] head = (double[]) goneHeads.get(h);" +
            "    if (best == null || Math.abs(m - head[2]) < Math.abs(m - best[2])) { best = head; }" +
            "    h = h + 1;" +
            "  }" +
            "  String kind = path.indexOf(\"" + ZOMBIE_FAMILY + "\") >= 0 ? \"zombie\" : \"not a zombie\";" +
            "  if (best == null || at == null) { return kind + \", no head gone on this floor\"; }" +
            "  double dx = ((double) at.x) - best[0];" +
            "  double dy = ((double) at.y) - best[1];" +
            "  return kind + \", made \" + ((int) (m - best[2])) + \"ms after the nearest head went, \"" +
            "      + (Math.round(Math.sqrt(dx * dx + dy * dy) * 10.0) / 10.0) + \" away; that head lasted \"" +
            "      + ((int) best[4]) + \"ms\" + (best[3] == 2.0 ? \", no totem near\" : \"\");" +
            "}", c));

        // the model binds its colour state when built, so edit the colour in place
        c.addMethod(CtNewMethod.make(
            "public static void paint(Object id, Object sprite) {" +
            "  try {" +
            "    Object model = modelOf(sprite);" +
            "    if (model == null) { return; }" +
            "    " + d.colorStateClass + " state = ((" + d.modelClass + ") model).getColorState();" +
            "    if (sharedWhite == null) {" +
            "      sharedWhite = Class.forName(\"" + d.colorStateClass + "\").getField(\"WHITE\").get(null);" +
            "    }" +
            "    if (state == null || state == sharedWhite) { return; }" +
            "    " + Discovery.COLOR4F + " colour = state.getColor();" +
            "    Object[] had = (Object[]) painted.get(id);" +
            "    if (had == null || had[0] != colour) {" +
            "      painted.put(id, new Object[] { colour, new " + Discovery.COLOR4F + "(" +
            "          colour.r, colour.g, colour.b, colour.a) });" +
            "    }" +
            "    colour.set(" + DEBUG_TINT + ", colour.a);" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void unpaint() {" +
            "  java.util.Iterator it = painted.values().iterator();" +
            "  while (it.hasNext()) {" +
            "    Object[] had = (Object[]) it.next();" +
            "    try {" +
            "      ((" + Discovery.COLOR4F + ") had[0]).set((" + Discovery.COLOR4F + ") had[1]);" +
            "    } catch (Throwable t) { }" +
            "  }" +
            "  painted.clear();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void sweep() {" +
            "  if (!debug) {" +
            "    if (!painted.isEmpty()) { unpaint(); }" +
            "    return;" +
            "  }" +
            "  try {" +
            "    Object view = seen();" +
            "    if (view == null) { return; }" +
            "    if (swept == null || swept.get() != view) {" +
            "      swept = new java.lang.ref.WeakReference(view);" +
            "      painted.clear();" +
            "    }" +
            "    java.util.Map sprites = spritesOf(view);" +
            "    if (sprites == null) { return; }" +
            "    java.util.Iterator it = sprites.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      Object sprite = e.getValue();" +
            "      if (sprite == null) { continue; }" +
            "      Object actor = ((" + d.spriteClass + ") sprite)." + d.spriteActorMethod + "();" +
            "      if (actor == null) { continue; }" +
            "      Object ref = ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "      String name = ref == null ? null : ((" + Discovery.CONFIG_REF + ") ref).getName();" +
            "      int id = ((Integer) e.getKey()).intValue();" +
            "      if (raised(id, actor) || name != null && verdict(id, actor, ref, name, view) != null) {" +
            "        paint(e.getKey(), sprite);" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String nameOf(int pawn) {" +
            "  try {" +
            "    if (pawn == 0) { return null; }" +
            "    Object p = place();" +
            "    if (p == null) { return null; }" +
            "    java.util.Iterator it = ((" + Discovery.PLACE_OBJECT + ") p).occupantInfo.iterator();" +
            "    while (it.hasNext()) {" +
            "      Object entry = it.next();" +
            "      if (!(entry instanceof " + Discovery.OCCUPANT_INFO + ")) { continue; }" +
            "      " + Discovery.OCCUPANT_INFO + " who = (" + Discovery.OCCUPANT_INFO + ") entry;" +
            "      if (who.pawnId != pawn) { continue; }" +
            "      if (who.username == null) { return null; }" +
            "      return who.username.toString();" +
            "    }" +
            "    return null;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int pips(int amount) {" +
            "  int sum = 0;" +
            "  int k = 0;" +
            "  while (k < 4) {" +
            "    sum = sum + ((amount >> (k * 5)) & 15);" +
            "    k = k + 1;" +
            "  }" +
            "  return sum;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void keep(int kind, String who, long value, long when) {" +
            "  taken_in = taken_in + 1;" +
            "  stirred = when;" +
            "  floorBook.add(kind, who, value, when);" +
            "  runBook.add(kind, who, value, when);" +
            "  if (kind == 0) { floorBook.hold(who, floorBook.rate(who, when, LIVE)); }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void noteHit(String by, int victim, long amount) {" +
            "  if (!debug) { return; }" +
            "  try {" +
            "    lastHits.put(by, new Object[] { seenPath, leaves(seenRef, seenPath), seenRule," +
            "        new Long(amount), new Integer(victim)," +
            "        Boolean.valueOf(raised(victim, seenActor))," +
            "        headGap(seenActor, seenPath), seenActor });" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void record(" + Discovery.DAMAGE_EVENT + " e) {" +
            "  try {" +
            "    if (fAmount == null) {" +
            "      fAmount = grab(e.getClass(), \"_amount\");" +
            "      fVictim = grab(e.getClass(), \"_actorId\");" +
            "      fSource = grab(e.getClass(), \"_sourceId\");" +
            "    }" +
            "    int amount = fAmount.getInt(e);" +
            "    int victim = fVictim.getInt(e);" +
            "    int source = fSource.getInt(e);" +
            "    String named = e.getName();" +
            "    boolean hit = \"damage\".equals(named);" +
            "    String hurt = nameOf(victim);" +
            "    boolean onPlayer = hurt != null;" +
            // hits on knights pack per-type pips into the low 20 bits
            "    boolean packed = (amount >> 20) != 0;" +
            "    long real = packed ? ((long) (amount >> 20)) : ((long) amount);" +
            "    if (real <= 0L) { return; }" +
            "    if (!hit) { return; }" +
            "    String by = nameOf(source);" +
            // the game kills things outright with fixed hits of 99,999 and up (railgun
            // auto-turrets do 999,999 to whatever they target); that is not damage anyone dealt
            "    if (real > " + MAX_HIT + "L) {" +
            "      dropped = dropped + 1;" +
            "      worthCounting(victim);" +
            "      droppedNote = (by != null ? by : \"#\" + source) + \" \" + real" +
            "          + \" on \" + (hurt != null ? hurt : (seenPath != null ? seenPath : \"#\" + victim));" +
            "      return;" +
            "    }" +
            "    long when = System.currentTimeMillis();" +
            "    if (onPlayer) {" +
            "      keep(1, hurt, packed ? ((long) pips(amount)) : real, when);" +
            "    } else if (packed) {" +
            "      strayed = strayed + real;" +
            "    } else {" +
            "      boolean worth = worthCounting(victim);" +
            "      if (by != null && seenPath != null) { noteHit(by, victim, real); }" +
            "      if (!worth) { return; }" +
            "      if (by != null) {" +
            "        keep(0, by, real, when);" +
            "      } else {" +
            "        strayed = strayed + real;" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + TALLY + " book() {" +
            "  if (segment == 1) { return lastBook; }" +
            "  if (segment == 2) { return runBook; }" +
            "  return floorBook;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int kind() {" +
            "  if (mode == 2) { return 1; }" +
            "  if (mode == 3) { return 2; }" +
            "  return 0;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String modeName() {" +
            "  if (mode == 1) { return \"DPS\"; }" +
            "  if (mode == 2) { return \"Pips lost\"; }" +
            "  if (mode == 3) { return \"Deaths\"; }" +
            "  return \"Damage done\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String segName() {" +
            "  if (segment == 1) { return \"last floor\"; }" +
            "  if (segment == 2) { return \"whole run\"; }" +
            "  return \"this floor\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String spanName() {" +
            "  if (mode == 1 && segment == 0) { return \"last \" + (LIVE / 1000L) + \"s\"; }" +
            "  return segName();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void cycleMode() { mode = (mode + 1) % 4; }", c));

        c.addMethod(CtNewMethod.make(
            "public static String nextMode() {" +
            "  int was = mode;" +
            "  mode = (mode + 1) % 4;" +
            "  String said = modeName();" +
            "  mode = was;" +
            "  return said;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String nextSpan() {" +
            "  int was = segment;" +
            "  segment = (segment + 1) % 3;" +
            "  String said = spanName();" +
            "  segment = was;" +
            "  return said;" +
            "}", c));
        c.addMethod(CtNewMethod.make(
            "public static void cycleSegment() { segment = (segment + 1) % 3; }", c));

        c.addMethod(CtNewMethod.make(
            "public static int oidOf(String who) {" +
            "  try {" +
            "    Object p = place();" +
            "    if (p == null) { return 0; }" +
            "    java.util.Iterator it = ((" + Discovery.PLACE_OBJECT + ") p).occupantInfo.iterator();" +
            "    while (it.hasNext()) {" +
            "      Object entry = it.next();" +
            "      if (!(entry instanceof " + Discovery.OCCUPANT_INFO + ")) { continue; }" +
            "      " + Discovery.OCCUPANT_INFO + " info = (" + Discovery.OCCUPANT_INFO + ") entry;" +
            "      if (info.username == null || !who.equals(info.username.toString())) { continue; }" +
            "      return info.bodyOid == null ? 0 : info.bodyOid.intValue();" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return 0;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object named(String who) {" +
            "  try {" +
            "    if (party == null) { return null; }" +
            "    java.util.Iterator it = ((" + Discovery.PARTY_OBJECT + ") party).members.iterator();" +
            "    while (it.hasNext()) {" +
            "      Object member = it.next();" +
            "      Object called = peekAt(member, \"name\");" +
            "      if (called != null && who.equals(called.toString())) { return member; }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean here(String who) {" +
            "  return oidOf(who) != 0 || named(who) != null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object memberOf(String who) {" +
            "  try {" +
            "    int oid = oidOf(who);" +
            "    if (party == null) { return null; }" +
            "    if (oid != 0) {" +
            "      java.util.Iterator it = ((" + Discovery.PARTY_OBJECT + ") party).members.iterator();" +
            "      while (it.hasNext()) {" +
            "        Object member = it.next();" +
            "        if (peek(member, \"_playerOid\") == oid) { return member; }" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return named(who);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int floorOf(Object object) {" +
            "  try {" +
            "    if (object instanceof " + Discovery.LEVEL_PARTY + ") {" +
            "      " + Discovery.LEVEL_PARTY + " level = (" + Discovery.LEVEL_PARTY + ") object;" +
            "      if (level.floor > 0) { return level.floor; }" +
            "      if (level.actualFloor > 0) { return level.actualFloor; }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return 0;" +
            "}", c));

        c.addField(CtField.make(
            "public static String[] paletteHues = \"" + PALETTE + "\".split(\",\");", c));
        c.addField(CtField.make("public static java.util.HashMap picked = new java.util.HashMap();", c));

        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " fromHue(float degrees, float sat, float val) {" +
            "  int rgb = java.awt.Color.HSBtoRGB(degrees / 360.0f, sat, val);" +
            "  return new " + Discovery.COLOR4F + "(" +
            "      ((float) ((rgb >> 16) & 255)) / 255.0f," +
            "      ((float) ((rgb >> 8) & 255)) / 255.0f," +
            "      ((float) (rgb & 255)) / 255.0f, 1.0f);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " chosen(String who, java.awt.Color raw) {" +
            "  Object had = picked.get(who);" +
            "  if (had == null) {" +
            "    float[] hsb = java.awt.Color.RGBtoHSB(" +
            "        raw.getRed(), raw.getGreen(), raw.getBlue(), null);" +
            "    float want = hsb[0] * 360.0f;" +
            "    int best = 0;" +
            "    float near = 1000.0f;" +
            "    int i = 0;" +
            "    while (i < paletteHues.length) {" +
            "      float gap = Math.abs(Float.parseFloat(paletteHues[i]) - want);" +
            "      if (gap > 180.0f) { gap = 360.0f - gap; }" +
            "      if (gap < near && !picked.containsValue(new Integer(i))) { near = gap; best = i; }" +
            "      i = i + 1;" +
            "    }" +
            "    had = new Integer(best);" +
            "    picked.put(who, had);" +
            "  }" +
            "  return fromHue(Float.parseFloat(paletteHues[((Integer) had).intValue()]), 0.72f, 0.92f);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " vivid(java.awt.Color raw) {" +
            "  float[] hsb = java.awt.Color.RGBtoHSB(" +
            "      raw.getRed(), raw.getGreen(), raw.getBlue(), null);" +
            "  float sat = hsb[1];" +
            "  if (sat > 0.04f) { sat = 0.56f + sat * 0.34f; }" +
            "  if (sat > 1.0f) { sat = 1.0f; }" +
            "  float val = 0.76f + hsb[2] * 0.20f;" +
            "  if (val > 1.0f) { val = 1.0f; }" +
            "  int rgb = java.awt.Color.HSBtoRGB(hsb[0], sat, val);" +
            "  return new " + Discovery.COLOR4F + "(" +
            "      ((float) ((rgb >> 16) & 255)) / 255.0f," +
            "      ((float) ((rgb >> 8) & 255)) / 255.0f," +
            "      ((float) (rgb & 255)) / 255.0f, 1.0f);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object tintOf(String who) {" +
            "  Object member = memberOf(who);" +
            "  int colour = member == null ? Integer.MIN_VALUE : ((" + Discovery.PARTY_MEMBER + ") member).color;" +
            "  Object[] had = (Object[]) tints.get(who);" +
            "  if (had != null && (member == null || ((Integer) had[0]).intValue() == colour)) {" +
            "    return had[1] == NONE ? null : had[1];" +
            "  }" +
            "  Object made = null;" +
            "  try {" +
            "    if (member != null) {" +
            "      if (had != null) { picked.remove(who); }" +
            "      Object paint = ((" + d.glCtxClass + ") ctx).getColorPository()." + d.colorLookupMethod + "(colour);" +
            "      if (paint != null) {" +
            "        java.awt.Color rgb = ((" + d.colorizationClass + ") paint)." + d.colorizedRootMethod + "();" +
            "        if (rgb != null) {" +
            "          made = " + (CHOSEN_COLOURS ? "chosen(who, rgb)" : "vivid(rgb)") + ";" +
            "        }" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  tints.put(who, new Object[] { new Integer(colour), made == null ? NONE : made });" +
            "  return made;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object weaponIcon(String who) {" +
            "  try {" +
            "    Object member = memberOf(who);" +
            "    if (member == null) {" +
            "      return null;" +
            "    }" +
            "    Object[] arms = ((" + Discovery.PARTY_MEMBER + ") member).weapons;" +
            "    Object ref = null;" +
            "    int i = 0;" +
            "    while (arms != null && i < arms.length) {" +
            "      if (arms[i] != null) { ref = arms[i]; break; }" +
            "      i = i + 1;" +
            "    }" +
            "    if (ref == null) {" +
            "      return null;" +
            "    }" +
            "    Object had = badges.get(ref);" +
            "    if (had != null) { return had == NONE ? null : had; }" +
            "    Object made = null;" +
            "    " + d.glCtxClass + " gl = (" + d.glCtxClass + ") ctx;" +
            "    Object item = " + Discovery.ITEM + "." + d.itemResolveMethod + "(gl.getConfigManager(), (" + Discovery.CONFIG_REF + ") ref);" +
            "    if (item == null) {" +
            "    } else if (((" + Discovery.ITEM_ORIGINAL + ") item).icon == null) {" +
            "    } else {" +
            "      made = ((" + Discovery.ITEM_ORIGINAL + ") item).icon.getImage(gl);" +
            "    }" +
            "    badges.put(ref, made == null ? NONE : made);" +
            "    return made;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object iconOfItem(Object item) {" +
            "  try {" +
            "    if (item == null || ((" + Discovery.ITEM_ORIGINAL + ") item).icon == null) { return null; }" +
            "    return ((" + Discovery.ITEM_ORIGINAL + ") item).icon.getImage((" + d.glCtxClass + ") ctx);" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addField(CtField.make(
            "public static java.util.HashMap byModel = new java.util.HashMap();", c));
        c.addField(CtField.make("public static java.util.ArrayList pending = null;", c));
        c.addField(CtField.make("public static int walked = 0;", c));
        c.addField(CtField.make("public static int shared = 0;", c));
        c.addField(CtField.make("public static boolean indexed = false;", c));
        c.addField(CtField.make(
            "public static String[] templates = \"" + TEMPLATES + "\".split(\",\");", c));

        c.addMethod(CtNewMethod.make(
            "public static String modelKey(Object reference) {" +
            "  try {" +
            "    " + Discovery.CONFIG_REF + " ref = (" + Discovery.CONFIG_REF + ") reference;" +
            "    StringBuffer out = new StringBuffer(ref.getName());" +
            "    java.util.Map args = ref." + d.refArgsMethod + "();" +
            "    if (args == null) { return out.toString(); }" +
            "    java.util.ArrayList keys = new java.util.ArrayList(args.keySet());" +
            "    java.util.Collections.sort(keys);" +
            "    int i = 0;" +
            "    while (i < keys.size()) {" +
            "      Object k = keys.get(i);" +
            "      i = i + 1;" +
            "      if (\"" + Discovery.WEARER_ARG + "\".equals(k)) { continue; }" +
            "      out.append('|').append(k).append('=').append(args.get(k));" +
            "    }" +
            "    return out.toString();" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean isTemplate(Object entry) {" +
            "  try {" +
            "    String named = ((" + Discovery.MANAGED_CONFIG + ") entry).getName();" +
            "    if (named == null) { return true; }" +
            "    int i = 0;" +
            "    while (i < templates.length) {" +
            "      if (named.indexOf(templates[i]) != -1) { return true; }" +
            "      i = i + 1;" +
            "    }" +
            "    return named.endsWith(\"Base\") || named.endsWith(\"Recolor\");" +
            "  } catch (Throwable t) { return false; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void gatherItems() {" +
            "  pending = new java.util.ArrayList();" +
            "  try {" +
            "    Object cfgmgr = ((" + d.glCtxClass + ") ctx).getConfigManager();" +
            "    java.util.ArrayList groups = new java.util.ArrayList();" +
            "    Object one = ((" + Discovery.CONFIG_MANAGER + ") cfgmgr)." + d.itemGroupMethod + "(" + Discovery.ITEM_CONFIG + ".class);" +
            "    if (one != null) { groups.add(one); }" +
            "    Object[] more = ((" + Discovery.CONFIG_MANAGER + ") cfgmgr)." + d.itemGroupsMethod + "(" + Discovery.ITEM_CONFIG + ".class);" +
            "    int g = 0;" +
            "    while (more != null && g < more.length) {" +
            "      if (more[g] != null && !groups.contains(more[g])) { groups.add(more[g]); }" +
            "      g = g + 1;" +
            "    }" +
            "    g = 0;" +
            "    while (g < groups.size()) {" +
            "      java.util.Iterator it = ((" + Discovery.CONFIG_GROUP + ") groups.get(g))." + d.groupListMethod + "().iterator();" +
            "      g = g + 1;" +
            "      while (it.hasNext()) {" +
            "        Object entry = it.next();" +
            "        if (entry instanceof " + Discovery.ITEM_CONFIG + " && !isTemplate(entry)) {" +
            "          pending.add(entry);" +
            "        }" +
            "      }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void indexStep() {" +
            "  if (indexed || ctx == null) { return; }" +
            "  try {" +
            "    if (pending == null) { gatherItems(); }" +
            "    Object cfgmgr = ((" + d.glCtxClass + ") ctx).getConfigManager();" +
            "    int did = 0;" +
            "    while (walked < pending.size() && did < " + INDEX_SLICE + ") {" +
            "      Object entry = pending.get(walked);" +
            "      walked = walked + 1;" +
            "      did = did + 1;" +
            "      try {" +
            "        Object worn = ((" + Discovery.ITEM_CONFIG + ") entry)." + d.configOwnOriginalMethod + "((" + Discovery.CONFIG_MANAGER + ") cfgmgr);" +
            "        if (worn == null || ((" + Discovery.ITEM_ORIGINAL + ") worn).icon == null) { continue; }" +
            "        Object model = peekAt(worn, \"model\");" +
            "        if (model == null) { continue; }" +
            "        String key = modelKey(model);" +
            "        if (key == null) { continue; }" +
            "        Object rival = byModel.get(key);" +
            "        if (rival == null) { byModel.put(key, worn); continue; }" +
            "        shared = shared + 1;" +
            "        if (((" + Discovery.ITEM_ORIGINAL + ") worn).rarity > ((" + Discovery.ITEM_ORIGINAL + ") rival).rarity) { byModel.put(key, worn); }" +
            "      } catch (Throwable t) { }" +
            "    }" +
            "    if (walked >= pending.size()) {" +
            "      indexed = true;" +
            "      pending = null;" +
            "    }" +
            "  } catch (Throwable t) { indexed = true; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object iconFromModel(Object reference) {" +
            "  try {" +
            "    if (reference == null) { return null; }" +
            "    if (!indexed) { return null; }" +
            "    String key = modelKey(reference);" +
            "    Object worn = key == null ? null : byModel.get(key);" +
            "    if (worn == null) {" +
            "      return null;" +
            "    }" +
            "    Object made = iconOfItem(worn);" +
            "    return made;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object myHelm() {" +
            "  try {" +
            "    Object me = ((" + Discovery.CTX + ") ctx)." + d.selfMethod + "();" +
            "    if (me == null) { return null; }" +
            "    Object worn = ((" + Discovery.PLAYER_OBJECT + ") me)." + d.visibleItemMethod + "(" + Discovery.HELM_PICK + ", null);" +
            "    if (worn == null) { return null; }" +
            "    Object key = \"mine\" + worn.hashCode();" +
            "    Object had = badges.get(key);" +
            "    if (had != null) { return had == NONE ? null : had; }" +
            "    Object item = ((" + Discovery.ITEM + ") worn)." + d.itemOwnConfigMethod + "(((" + d.glCtxClass + ") ctx).getConfigManager());" +
            "    Object made = iconOfItem(item);" +
            "    badges.put(key, made == null ? NONE : made);" +
            "    return made;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean isMe(String who) {" +
            "  try {" +
            "    Object me = ((" + Discovery.CTX + ") ctx)." + d.selfMethod + "();" +
            "    if (me == null) { return false; }" +
            "    Object called = ((" + Discovery.PLAYER_OBJECT + ") me).knight;" +
            "    if (called != null && who.equals(called.toString())) { return true; }" +
            "    int mine = ((" + Discovery.DOBJECT + ") me)." + d.oidMethod + "();" +
            "    int theirs = oidOf(who);" +
            "    return mine != 0 && mine == theirs;" +
            "  } catch (Throwable t) { return false; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.util.HashSet crewOf(Object object) {" +
            "  java.util.HashSet found = new java.util.HashSet();" +
            "  try {" +
            "    java.util.Iterator it = ((" + Discovery.PARTY_OBJECT + ") object).members.iterator();" +
            "    while (it.hasNext()) {" +
            "      Object called = peekAt(it.next(), \"name\");" +
            "      if (called == null) { continue; }" +
            "      String said = called.toString();" +
            "      if (!isMe(said)) { found.add(said); }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return found;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void align(" + Discovery.LABEL + " label, int how) {" +
            "  try {" +
            "    java.lang.reflect.Field f = grab(label.getClass(), \"_haligns\");" +
            "    if (f == null) { return; }" +
            "    int[] now = (int[]) f.get(label);" +
            "    if (now == null) { return; }" +
            "    int i = 0;" +
            "    while (i < now.length) { now[i] = how; i = i + 1; }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addField(CtField.make("public static int padW = 0;", c));

        c.addMethod(CtNewMethod.make(
            "public static int wide(" + Discovery.LABEL + " label) {" +
            "  try {" +
            "    " + d.dimensionClass + " want = label.getPreferredSize(-1, -1);" +
            "    return want == null ? 0 : want.width;" +
            "  } catch (Throwable t) { return 0; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void measurePad(" + Discovery.LABEL + " label) {" +
            "  try {" +
            "    if (padW > 0) { return; }" +
            "    String was = label.getText();" +
            "    label.setText(\"" + BUFFER + "\");" +
            "    int with = wide(label);" +
            "    label.setText(\"\");" +
            "    int without = wide(label);" +
            "    label.setText(was == null ? \"\" : was);" +
            "    if (with > without) { padW = with - without; }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        // pad with spaces or the first and last glyphs get clipped
        c.addMethod(CtNewMethod.make(
            "public static String padded(String text) {" +
            "  return \"" + BUFFER + "\" + text + \"" + BUFFER + "\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String myName() {" +
            "  try {" +
            "    Object me = ((" + Discovery.CTX + ") ctx)." + d.selfMethod + "();" +
            "    Object called = me == null ? null : ((" + Discovery.PLAYER_OBJECT + ") me).knight;" +
            "    return called == null ? \"\" : called.toString();" +
            "  } catch (Throwable t) { return \"\"; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object helmIcon(String who) {" +
            "  try {" +
            "    if (!(party instanceof " + Discovery.LEVEL_PARTY + ")) {" +
            "      return null;" +
            "    }" +
            "    Object member = memberOf(who);" +
            "    if (member == null) { return null; }" +
            "    Object kit = ((" + Discovery.LEVEL_PARTY + ") party).equipment.get(new Integer(peek(member, \"_playerOid\")));" +
            "    if (kit == null) {" +
            "      return null;" +
            "    }" +
            "    Integer slot = new Integer(" + Discovery.HELM_SLOT + ");" +
            "    java.util.Map worn = ((" + Discovery.PLAYER_EQUIP + ") kit).visibleEquipment;" +
            "    java.util.Map whole = ((" + Discovery.PLAYER_EQUIP + ") kit).items;" +
            "    Object ref = worn == null ? null : worn.get(slot);" +
            "    Object held = whole == null ? null : whole.get(slot);" +
            "    Object key = ref == null ? held : ref;" +
            "    if (key == null) {" +
            "      return null;" +
            "    }" +
            "    Object had = badges.get(key);" +
            "    if (had != null) { return had == NONE ? null : had; }" +
            "    Object made = null;" +
            "    " + d.glCtxClass + " gl = (" + d.glCtxClass + ") ctx;" +
            "    Object item = ref != null" +
            "        ? " + Discovery.ITEM + "." + d.itemResolveMethod + "(gl.getConfigManager(), (" + Discovery.CONFIG_REF + ") ref)" +
            "        : ((" + Discovery.ITEM + ") held)." + d.itemOwnConfigMethod + "(gl.getConfigManager());" +
            "    if (item != null && ((" + Discovery.ITEM_ORIGINAL + ") item).icon != null) {" +
            "      made = ((" + Discovery.ITEM_ORIGINAL + ") item).icon.getImage(gl);" +
            "    }" +
            "    badges.put(key, made == null ? NONE : made);" +
            "    return made;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object helmByModel(String who) {" +
            "  try {" +
            "    Object member = memberOf(who);" +
            "    if (member == null) { return null; }" +
            "    Object ref = ((" + Discovery.PARTY_MEMBER + ") member).helm;" +
            "    if (ref == null) { return null; }" +
            "    Object had = badges.get(ref);" +
            "    if (had != null) { return had == NONE ? null : had; }" +
            "    Object made = iconFromModel(ref);" +
            "    if (made != null || indexed) { badges.put(ref, made == null ? NONE : made); }" +
            "    return made;" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static Object badgeOf(String who) {" +
            "  Object worn = isMe(who) ? myHelm() : null;" +
            "  if (worn == null) { worn = helmIcon(who); }" +
            "  if (worn == null) { worn = helmByModel(who); }" +
            "  if (worn == null) { worn = weaponIcon(who); }" +
            "  if (worn != null) { lastSeen.put(who, worn); return worn; }" +
            "  return lastSeen.get(who);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int slotOf(String who) {" +
            "  Object had = slots.get(who);" +
            "  if (had != null) { return ((Integer) had).intValue(); }" +
            "  int next = slots.size();" +
            "  slots.put(who, new Integer(next));" +
            "  return next;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static long weight(String who) {" +
            "  " + TALLY + " b = book();" +
            "  long v = b.get(kind(), who);" +
            "  if (mode != 1) { return v; }" +
            "  if (segment == 0) { return b.heldRate(who); }" +
            "  return (long) (((float) v) / b.seconds(who));" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String trim(double x) {" +
            "  long tenths = (long) (x * 10.0 + 0.5);" +
            "  if (x >= 100.0 || tenths % 10L == 0L) { return String.valueOf(tenths / 10L); }" +
            "  return (tenths / 10L) + \".\" + (tenths % 10L);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String pretty(long v) {" +
            "  if (v < 1000L) { return String.valueOf(v); }" +
            "  if (v < 1000000L) { return trim(((double) v) / 1000.0) + \"k\"; }" +
            "  return trim(((double) v) / 1000000.0) + \"M\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean ahead(String a, String b) {" +
            "  boolean ha = here(a);" +
            "  boolean hb = here(b);" +
            "  if (ha != hb) { return ha; }" +
            "  return weight(a) > weight(b);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.util.ArrayList ranked() {" +
            "  java.util.ArrayList ids = book().who(kind());" +
            "  int i = 0;" +
            "  while (i < ids.size()) {" +
            "    int best = i;" +
            "    int j = i + 1;" +
            "    while (j < ids.size()) {" +
            "      if (ahead((String) ids.get(j), (String) ids.get(best))) { best = j; }" +
            "      j = j + 1;" +
            "    }" +
            "    if (best != i) {" +
            "      Object swap = ids.get(i);" +
            "      ids.set(i, ids.get(best));" +
            "      ids.set(best, swap);" +
            "    }" +
            "    i = i + 1;" +
            "  }" +
            "  return ids;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String figure(String who) {" +
            "  long mine = weight(who);" +
            "  if (mode == 3) { return String.valueOf(mine); }" +
            "  return pretty(mine) + (mode == 1 ? \"/s\" : \"\");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String share(String who) {" +
            "  if (mode == 3 || !here(who)) { return \"\"; }" +
            "  long mine = weight(who);" +
            "  long all = 0L;" +
            "  java.util.Iterator it = book().who(kind()).iterator();" +
            "  while (it.hasNext()) { all = all + weight((String) it.next()); }" +
            "  return (all <= 0L ? 0 : (int) ((mine * 100L) / all)) + \"%\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String ruleText(String rule) {" +
            "  String what = rule.substring(1);" +
            "  String where = \"\";" +
            "  int at = what.indexOf('^');" +
            "  if (at >= 0) {" +
            "    where = \"  [\" + what.substring(at + 1).replace(\",\", \", \") + \"]\";" +
            "    what = what.substring(0, at);" +
            "  }" +
            "  return rule.substring(0, 1) + \" \" + what + where;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void wrapInto(java.util.ArrayList out, String head, String text) {" +
            "  String lead = head;" +
            "  String rest = text;" +
            "  while (rest.length() > 0) {" +
            "    int room = " + DEBUG_CHARS + " - lead.length();" +
            "    if (rest.length() <= room) { out.add(lead + rest); return; }" +
            "    int cut = -1;" +
            "    int i = room;" +
            "    while (cut < 0 && i > room / 3) {" +
            "      char ch = rest.charAt(i - 1);" +
            "      if (ch == '/' || ch == ',') { cut = i; }" +
            "      i = i - 1;" +
            "    }" +
            "    i = room;" +
            "    while (cut < 0 && i > room / 3) {" +
            "      if (rest.charAt(i - 1) == ' ') { cut = i; }" +
            "      i = i - 1;" +
            "    }" +
            "    if (cut < 0) { cut = room; }" +
            "    out.add(lead + rest.substring(0, cut));" +
            "    rest = rest.substring(cut).trim();" +
            "    lead = \"" + DEBUG_INDENT + "\";" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String shown(Object v) {" +
            "  if (v == null) { return \"null\"; }" +
            "  String said;" +
            "  if (v instanceof Object[]) {" +
            "    Object[] all = (Object[]) v;" +
            "    StringBuffer b = new StringBuffer(\"[\");" +
            "    int i = 0;" +
            "    while (i < all.length) {" +
            "      if (i > 0) { b.append(\", \"); }" +
            "      b.append(String.valueOf(all[i]));" +
            "      i = i + 1;" +
            "    }" +
            "    said = b.append(\"]\").toString();" +
            "  } else if (v instanceof int[]) {" +
            "    int[] all = (int[]) v;" +
            "    StringBuffer b = new StringBuffer(\"[\");" +
            "    int i = 0;" +
            "    while (i < all.length) {" +
            "      if (i > 0) { b.append(\", \"); }" +
            "      b.append(all[i]);" +
            "      i = i + 1;" +
            "    }" +
            "    said = b.append(\"]\").toString();" +
            "  } else {" +
            "    said = String.valueOf(v);" +
            "  }" +
            "  if (said.length() > " + DEBUG_VALUE + ") {" +
            "    said = said.substring(0, " + DEBUG_VALUE + ") + \"...\";" +
            "  }" +
            "  return said;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String flagNames(Object actor, int flags) {" +
            "  StringBuffer b = new StringBuffer();" +
            "  int left = flags;" +
            "  Class k = actor.getClass();" +
            "  while (k != null && !k.getName().equals(\"java.lang.Object\")) {" +
            "    java.lang.reflect.Field[] fs = k.getDeclaredFields();" +
            "    int i = 0;" +
            "    while (i < fs.length) {" +
            "      java.lang.reflect.Field f = fs[i];" +
            "      i = i + 1;" +
            "      int mod = f.getModifiers();" +
            "      if (!java.lang.reflect.Modifier.isStatic(mod)" +
            "          || !java.lang.reflect.Modifier.isPublic(mod)" +
            "          || f.getType() != Integer.TYPE) { continue; }" +
            "      try {" +
            "        int bit = f.getInt(null);" +
            "        if (bit != 0 && (bit & (bit - 1)) == 0 && (flags & bit) != 0) {" +
            "          if (b.length() > 0) { b.append(' '); }" +
            "          b.append(f.getName());" +
            "          left = left & ~bit;" +
            "        }" +
            "      } catch (Throwable t) { }" +
            "    }" +
            "    k = k.getSuperclass();" +
            "  }" +
            "  if (left != 0) { b.append(b.length() > 0 ? \" \" : \"\").append(\"+\" + left); }" +
            "  return b.length() == 0 ? \"none\" : b.toString();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void actorLines(java.util.ArrayList out, Object actor) {" +
            "  if (actor == null) { out.add(\"actor  gone\"); return; }" +
            "  int flags = peek(actor, \"_flags\");" +
            "  wrapInto(out, \"actor  \", actor.getClass().getName());" +
            "  wrapInto(out, \"flags  \", flags + \" = \" + flagNames(actor, flags));" +
            "  Class k = actor.getClass();" +
            "  while (k != null && !k.getName().equals(\"java.lang.Object\")) {" +
            "    java.lang.reflect.Field[] fs = k.getDeclaredFields();" +
            "    int i = 0;" +
            "    while (i < fs.length) {" +
            "      java.lang.reflect.Field f = fs[i];" +
            "      i = i + 1;" +
            "      if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) { continue; }" +
            "      if (f.getName().equals(\"_flags\")) { continue; }" +
            "      try {" +
            "        f.setAccessible(true);" +
            "        wrapInto(out, f.getName() + \"  \", shown(f.get(actor)));" +
            "      } catch (Throwable t) { out.add(f.getName() + \"  unreadable\"); }" +
            "    }" +
            "    k = k.getSuperclass();" +
            "  }" +
            "}", c));

        // {made, id, x, y, raised}, oldest first
        c.addMethod(CtNewMethod.make(
            "public static java.util.ArrayList zombiesHere() {" +
            "  java.util.ArrayList out = new java.util.ArrayList();" +
            "  Object view = seen();" +
            "  java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "  if (sprites == null) { return out; }" +
            "  java.util.Iterator it = sprites.entrySet().iterator();" +
            "  while (it.hasNext()) {" +
            "    java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "    Object actor = e.getValue() == null ? null" +
            "        : ((" + d.spriteClass + ") e.getValue())." + d.spriteActorMethod + "();" +
            "    if (!(actor instanceof " + Discovery.MONSTER + ")) { continue; }" +
            "    Object ref = ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "    String name = ref == null ? null : ((" + Discovery.CONFIG_REF + ") ref).getName();" +
            "    if (name == null || name.indexOf(\"" + ZOMBIE_FAMILY + "\") < 0) { continue; }" +
            "    " + Discovery.VECTOR2F + " at = ((" + Discovery.ACTOR + ") actor)." + d.actorWhereMethod + "();" +
            "    int id = ((Integer) e.getKey()).intValue();" +
            "    double[] one = new double[] { (double) made(actor), (double) id," +
            "        at == null ? 0.0 : (double) at.x, at == null ? 0.0 : (double) at.y," +
            "        raised(id, actor) ? 1.0 : 0.0 };" +
            "    int i = out.size();" +
            "    while (i > 0 && ((double[]) out.get(i - 1))[0] > one[0]) { i = i - 1; }" +
            "    out.add(i, one);" +
            "  }" +
            "  return out;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static int[] roomCounts() {" +
            "  int[] n = new int[3];" +
            "  java.util.ArrayList zs = zombiesHere();" +
            "  n[1] = zs.size();" +
            "  int i = 0;" +
            "  while (i < zs.size()) { if (((double[]) zs.get(i))[4] != 0.0) { n[2] = n[2] + 1; } i = i + 1; }" +
            "  Object view = seen();" +
            "  java.util.Map sprites = view == null ? null : spritesOf(view);" +
            "  if (sprites == null) { return n; }" +
            "  java.util.Iterator it = sprites.values().iterator();" +
            "  while (it.hasNext()) {" +
            "    Object sprite = it.next();" +
            "    Object actor = sprite == null ? null : ((" + d.spriteClass + ") sprite)." + d.spriteActorMethod + "();" +
            "    Object ref = actor == null ? null : ((" + Discovery.ACTOR + ") actor)." + d.actorRefMethod + "();" +
            "    if (ref != null && \"" + Discovery.ZOMBIE_HEAD + "\".equals(((" + Discovery.CONFIG_REF + ") ref).getName())) { n[0] = n[0] + 1; }" +
            "  }" +
            "  return n;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String zombieGap(double[] head, java.util.ArrayList zs) {" +
            "  double[] best = null;" +
            "  int i = 0;" +
            "  while (i < zs.size()) {" +
            "    double[] z = (double[]) zs.get(i);" +
            "    if (best == null || Math.abs(z[0] - head[2]) < Math.abs(best[0] - head[2])) { best = z; }" +
            "    i = i + 1;" +
            "  }" +
            "  if (best == null) { return \"never\"; }" +
            "  double dx = best[2] - head[0];" +
            "  double dy = best[3] - head[1];" +
            "  return ((int) (best[0] - head[2])) + \"ms after (#\" + ((int) best[1]) + \", \"" +
            "      + (Math.round(Math.sqrt(dx * dx + dy * dy) * 10.0) / 10.0) + \" away)\";" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void timelineLines(java.util.ArrayList out) {" +
            "  if (!wantRevived) { out.add(\"no rule asks for @Revived\"); }" +
            "  java.util.ArrayList zs = zombiesHere();" +
            "  int from = goneHeads.size() - 6;" +
            "  if (from < 0) { from = 0; }" +
            "  if (goneHeads.isEmpty()) { out.add(\"no zombie head has gone on this floor\"); }" +
            "  int h = from;" +
            "  while (h < goneHeads.size()) {" +
            "    double[] head = (double[]) goneHeads.get(h);" +
            "    String fate = head[3] == 1.0 ? \"raised #\" + ((int) head[5])" +
            "        : head[3] == 2.0 ? \"no totem near, raised nothing\" : \"no zombie made then\";" +
            "    wrapInto(out, \"head  \", \"went \" + ((int) head[2]) + \", lasted \" + ((int) head[4])" +
            "        + \"ms, totem \" + (head[6] > 9999.0 ? \"none\" : String.valueOf(Math.round(head[6] * 10.0) / 10.0))" +
            "        + \" away; \" + fate + \"; nearest zombie made \" + zombieGap(head, zs));" +
            "    h = h + 1;" +
            "  }" +
            "  from = zs.size() - 8;" +
            "  if (from < 0) { from = 0; }" +
            "  if (zs.isEmpty()) { out.add(\"no zombie here\"); }" +
            "  int z = from;" +
            "  while (z < zs.size()) {" +
            "    double[] one = (double[]) zs.get(z);" +
            "    out.add(\"zombie  #\" + ((int) one[1]) + \" made \" + ((int) one[0])" +
            "        + (one[4] != 0.0 ? \", raised\" : \"\"));" +
            "    z = z + 1;" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.util.ArrayList debugLines() {" +
            "  java.util.ArrayList out = new java.util.ArrayList();" +
            "  if (debugPage == 1) { out.add(\"showing tint\"); return out; }" +
            "  try {" +
            "    String mark = party != null ? markOf(party) : current.mark;" +
            "    int floor = party != null ? floorOf(party) : 0;" +
            "    wrapInto(out, \"where  \", (mark.length() == 0 ? \"nowhere\" : mark)" +
            "        + (floor > 0 ? \"  floor \" + floor : \"\")" +
            "        + (party != null ? \"  depth \" + ((" + Discovery.PARTY_OBJECT + ") party).depth" +
            "            : \"\"));" +
            "    if (party instanceof " + Discovery.LEVEL_PARTY + ") {" +
            "      " + Discovery.LEVEL_PARTY + " level = (" + Discovery.LEVEL_PARTY + ") party;" +
            "      String called = level.pendingFloorName;" +
            "      wrapInto(out, \"floor  \", (called == null || called.length() == 0" +
            "          ? \"unnamed\" : called)" +
            "          + (level.floorType == null ? \"\" : \"  \" + level.floorType));" +
            "    }" +
            "    if (dropped > 0) {" +
            "      wrapInto(out, \"dropped  \", dropped + \" hit\" + (dropped == 1 ? \"\" : \"s\")" +
            "          + \" over " + MAX_HIT + " not counted, last: \" + droppedNote);" +
            "    }" +
            "    if (debugPage == 4) { timelineLines(out); return out; }" +
            "    int lone = 0;" +
            "    int h = 0;" +
            "    while (h < goneHeads.size()) {" +
            "      if (((double[]) goneHeads.get(h))[3] == 2.0) { lone = lone + 1; }" +
            "      h = h + 1;" +
            "    }" +
            "    int[] here = roomCounts();" +
            "    wrapInto(out, \"heads  \", here[0] + \" standing, \" + goneHeads.size() + \" gone, \"" +
            "        + lone + \" with no totem;  \"" +
            "        + here[1] + \" zombies here, \" + here[2] + \" raised, \"" +
            "        + painted.size() + \" painted\");" +
            "    Object[] hit = null;" +
            "    java.util.Iterator it = lastHits.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      if (isMe((String) e.getKey())) { hit = (Object[]) e.getValue(); break; }" +
            "    }" +
            "    if (hit == null) { out.add(\"hit  nothing of yours yet\"); return out; }" +
            "    if (debugPage == 3) { actorLines(out, hit[7]); return out; }" +
            "    wrapInto(out, \"hit  \", (String) hit[0]);" +
            "    String flag = (String) hit[1];" +
            "    wrapInto(out, \"flag  \", (flag == null || flag.length() == 0 ? \"none\" : flag)" +
            "        + (((Boolean) hit[5]).booleanValue() ? \", Revived\" : \"\")" +
            "        + \"    actor \" + hit[4] + \", took \" + hit[3]" +
            "        + (hit[7] == null ? \", out of range (remembered)\" : \"\"));" +
            "    wrapInto(out, \"head  \", (String) hit[6]);" +
            "    wrapInto(out, \"rule  \", hit[2] == null ? \"counted\"" +
            "        : \"left out by \" + ruleText((String) hit[2]));" +
            "  } catch (Throwable t) { out.add(\"debug failed: \" + t); }" +
            "  return out;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String precise(long v) {" +
            "  if (v < 1000L) { return String.valueOf(v); }" +
            "  double scaled = v < 1000000L ? (((double) v) / 1000.0) : (((double) v) / 1000000.0);" +
            "  long hundredths = (long) (scaled * 100.0 + 0.5);" +
            "  String tail = String.valueOf(hundredths % 100L);" +
            "  if (tail.length() < 2) { tail = \"0\" + tail; }" +
            "  return (hundredths / 100L) + \".\" + tail + (v < 1000000L ? \"k\" : \"M\");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static String nameOfOid(int oid) {" +
            "  try {" +
            "    Object p = place();" +
            "    if (p == null) { return null; }" +
            "    java.util.Iterator it = ((" + Discovery.PLACE_OBJECT + ") p).occupantInfo.iterator();" +
            "    while (it.hasNext()) {" +
            "      Object entry = it.next();" +
            "      if (!(entry instanceof " + Discovery.OCCUPANT_INFO + ")) { continue; }" +
            "      " + Discovery.OCCUPANT_INFO + " who = (" + Discovery.OCCUPANT_INFO + ") entry;" +
            "      if (who.bodyOid == null || who.bodyOid.intValue() != oid) { continue; }" +
            "      return who.username == null ? null : who.username.toString();" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void countFalls() {" +
            "  try {" +
            "    java.util.Iterator it = ((" + Discovery.PARTY_OBJECT + ") party).health.iterator();" +
            "    while (it.hasNext()) {" +
            "      " + Discovery.HEALTH + " h = (" + Discovery.HEALTH + ") it.next();" +
            "      String who = nameOfOid(peek(h, \"_playerOid\"));" +
            "      if (who == null) { continue; }" +
            "      boolean up = h.current > 0;" +
            "      Object was = standing.get(who);" +
            "      boolean before = was == null ? true : ((Boolean) was).booleanValue();" +
            "      standing.put(who, up ? Boolean.TRUE : Boolean.FALSE);" +
            "      if (before && !up) { keep(2, who, 1L, System.currentTimeMillis()); }" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void roll() {" +
            "  if (floorBook.total(0) <= 0L && floorBook.total(1) <= 0L) { return; }" +
            "  lastBook.clear();" +
            "  lastBook.soak(floorBook);" +
            "  floorBook.clear();" +
            "  strayed = 0L;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void drop() {" +
            "  try {" +
            "    if (party != null && watch != null) {" +
            "      ((" + Discovery.DOBJECT + ") party)." + d.removeListenerMethod +
                  "((" + d.listenerBaseIface + ") watch);" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  watch = null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void close() {" +
            "  try {" +
            "    if (meter == null) { return; }" +
            "    " + d.windowClass + " had = (" + d.windowClass + ") meter;" +
            "    meter = null;" +
            "    " + d.rootClass + " root = had.getRoot();" +
            "    if (root != null) {" +
            "      root.removeWindow(had);" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void leave() {" +
            "  parted = 0L;" +
            "  drop();" +
            "  close();" +
            "  if (party != null) {" +
            "    taken_in = taken_in + 1;" +
            "  }" +
            "  party = null;" +
            "  depthSeen = -1;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void prune() {" +
            "  while (runs.size() > " + KEPT_RUNS + ") {" +
            "    Object oldest = null;" +
            "    long stalest = 0L;" +
            "    java.util.Iterator it = runs.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      " + RUN + " held = (" + RUN + ") e.getValue();" +
            "      if (held == current) { continue; }" +
            "      if (oldest == null || held.when < stalest) {" +
            "        oldest = e.getKey();" +
            "        stalest = held.when;" +
            "      }" +
            "    }" +
            "    if (oldest == null) { return; }" +
            "    runs.remove(oldest);" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static boolean meets(java.util.Set a, java.util.Set b) {" +
            "  java.util.Iterator it = a.iterator();" +
            "  while (it.hasNext()) { if (b.contains(it.next())) { return true; } }" +
            "  return false;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + RUN + " adopt(int oid, String badge, int level," +
            " java.util.Set been, java.util.Set lot) {" +
            "  if (level <= 0) { return null; }" +
            "  java.util.Iterator it = runs.entrySet().iterator();" +
            "  while (it.hasNext()) {" +
            "    java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "    " + RUN + " held = (" + RUN + ") e.getValue();" +
            "    if (held.isEmpty() && !held.kept) { continue; }" +
            "    boolean shares = false;" +
            "    if (been != null) {" +
            "      java.util.Iterator walk = been.iterator();" +
            "      while (walk.hasNext()) {" +
            "        if (held.seen.contains(walk.next())) { shares = true; break; }" +
            "      }" +
            "    }" +
            "    boolean along = badge.length() > 0 && badge.equals(held.mark)" +
            "        && level > held.deepest" +
            "        && (held.crew.isEmpty() ? lot.isEmpty() : meets(held.crew, lot));" +
            "    if (!shares && !along) { continue; }" +
            "    runs.remove(e.getKey());" +
            "    held.oid = oid;" +
            "    runs.put(new Integer(oid), held);" +
            "    return held;" +
            "  }" +
            "  return null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void useRun(int oid, String badge, int level," +
            " java.util.Set been, java.util.Set lot) {" +
            "  if (oid == 0) { return; }" +
            "  Integer key = new Integer(oid);" +
            "  " + RUN + " had = (" + RUN + ") runs.get(key);" +
            "  if (had == null) { had = adopt(oid, badge, level, been, lot); }" +
            "  if (had != null && level > 0) {" +
            "    boolean back = level < had.deepest;" +
            "    boolean other = badge.length() > 0 && had.mark.length() > 0" +
            "        && !badge.equals(had.mark);" +
            "    if (back || other) {" +
            "      runs.remove(key);" +
            "      had = null;" +
            "    }" +
            "  }" +
            "  if (had == null) {" +
            "    had = new " + RUN + "();" +
            "    had.oid = oid;" +
            "    runs.put(key, had);" +
            "  }" +
            "  if (current != had) {" +
            "    standing.clear();" +
            "  }" +
            "  current = had;" +
            "  floorBook = had.floor;" +
            "  lastBook = had.last;" +
            "  runBook = had.run;" +
            "  had.when = System.currentTimeMillis();" +
            "  prune();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void noteRun(boolean stepped) {" +
            "  try {" +
            "    if (party == null) { return; }" +
            "    int floor = floorOf(party);" +
            "    int deep = ((" + Discovery.PARTY_OBJECT + ") party).depth;" +
            "    int level = floor > 0 ? floor : deep;" +
            "    String badge = markOf(party);" +
            "    java.util.Set been = ((" + Discovery.PARTY_OBJECT + ") party).visited;" +
            "    java.util.HashSet lot = crewOf(party);" +
            "    useRun(((" + Discovery.DOBJECT + ") party)." + d.oidMethod + "()," +
            "        badge, level, been, lot);" +
            "    if (level <= 0) { return; }" +
            "    if (level > current.deepest) { current.deepest = level; }" +
            "    current.mark = badge;" +
            "    if (been != null) { current.seen.addAll(been); }" +
            "    current.crew.clear();" +
            "    current.crew.addAll(lot);" +
            "    if (stepped) {" +
            "      taken_in = taken_in + 1;" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void pump() {" +
            "  try {" +
            "    if (party == null) { return; }" +
            "    int floor = floorOf(party);" +
            "    int now = floor > 0 ? floor : ((" + Discovery.PARTY_OBJECT + ") party).depth;" +
            "    countFalls();" +
            "    if (now == depthSeen) { return; }" +
            "    boolean stepped = depthSeen > 0 && now > 0;" +
            "    seeing = null;" +
            "    looked = false;" +
            "    if (stepped) { roll(); }" +
            "    depthSeen = now;" +
            "    noteRun(stepped);" +
            "  } catch (Throwable t) { }" +
            "}", c));
    }

    private static void fillRun(CtClass c) throws Exception {
        c.addField(CtField.make("public int oid = 0;", c));
        c.addField(CtField.make("public String mark = \"\";", c));
        c.addField(CtField.make("public int deepest = 0;", c));
        c.addField(CtField.make("public long when = 0L;", c));
        // wiped with the reset button: still the live run, though empty
        c.addField(CtField.make("public boolean kept = false;", c));
        c.addField(CtField.make(
            "public java.util.HashSet crew = new java.util.HashSet();", c));
        c.addField(CtField.make(
            "public java.util.HashSet seen = new java.util.HashSet();", c));
        c.addField(CtField.make("public " + TALLY + " floor = new " + TALLY + "();", c));
        c.addField(CtField.make("public " + TALLY + " last = new " + TALLY + "();", c));
        c.addField(CtField.make("public " + TALLY + " run = new " + TALLY + "();", c));
        // "scene:id" -> path index * 2, +1 if raised
        c.addField(CtField.make("public java.util.LinkedHashMap known = new java.util.LinkedHashMap();", c));
        c.addField(CtField.make("public java.util.ArrayList paths = new java.util.ArrayList();", c));
        c.addConstructor(CtNewConstructor.make("public " + RUN_N + "() { }", c));

        c.addMethod(CtNewMethod.make(
            "public void remember(String key, String path, boolean raised) {" +
            "  int i = this.paths.indexOf(path);" +
            "  if (i < 0) { this.paths.add(path); i = this.paths.size() - 1; }" +
            "  Object had = this.known.get(key);" +
            "  int code = i * 2 + (raised ? 1 : 0);" +
            "  if (had != null) { code = code | (((Integer) had).intValue() & 1); }" +
            "  this.known.put(key, new Integer(code));" +
            "  if (this.known.size() > " + KNOWN_CAP + ") {" +
            "    java.util.Iterator it = this.known.keySet().iterator();" +
            "    it.next();" +
            "    it.remove();" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public String recall(String key) {" +
            "  Object had = this.known.get(key);" +
            "  if (had == null) { return null; }" +
            "  int code = ((Integer) had).intValue();" +
            "  return (String) this.paths.get(code / 2);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public boolean recallRaised(String key) {" +
            "  Object had = this.known.get(key);" +
            "  return had != null && (((Integer) had).intValue() & 1) == 1;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public boolean isEmpty() {" +
            "  return this.run.isEmpty() && this.last.isEmpty() && this.floor.isEmpty();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void save(java.util.Properties out, String prefix) {" +
            "  out.setProperty(prefix + \".mark\", this.mark);" +
            "  out.setProperty(prefix + \".deepest\", String.valueOf(this.deepest));" +
            "  out.setProperty(prefix + \".when\", String.valueOf(this.when));" +
            "  out.setProperty(prefix + \".kept\", this.kept ? \"1\" : \"0\");" +
            "  StringBuffer names = new StringBuffer();" +
            "  java.util.Iterator it = this.crew.iterator();" +
            "  while (it.hasNext()) {" +
            "    if (names.length() > 0) { names.append('|'); }" +
            "    names.append(it.next());" +
            "  }" +
            "  out.setProperty(prefix + \".crew\", names.toString());" +
            "  StringBuffer trail = new StringBuffer();" +
            "  java.util.Iterator walk = this.seen.iterator();" +
            "  while (walk.hasNext()) {" +
            "    if (trail.length() > 0) { trail.append('|'); }" +
            "    trail.append(walk.next());" +
            "  }" +
            "  out.setProperty(prefix + \".trail\", trail.toString());" +
            "  StringBuffer table = new StringBuffer();" +
            "  int p = 0;" +
            "  while (p < this.paths.size()) {" +
            "    if (p > 0) { table.append('|'); }" +
            "    table.append(this.paths.get(p));" +
            "    p = p + 1;" +
            "  }" +
            "  out.setProperty(prefix + \".paths\", table.toString());" +
            "  StringBuffer seenHere = new StringBuffer();" +
            "  java.util.Iterator k = this.known.entrySet().iterator();" +
            "  while (k.hasNext()) {" +
            "    java.util.Map$Entry e = (java.util.Map$Entry) k.next();" +
            "    if (seenHere.length() > 0) { seenHere.append('|'); }" +
            "    seenHere.append(e.getKey()).append('=').append(e.getValue());" +
            "  }" +
            "  out.setProperty(prefix + \".known\", seenHere.toString());" +
            "  this.run.save(out, prefix + \".run\");" +
            "  this.last.save(out, prefix + \".last\");" +
            "  this.floor.save(out, prefix + \".floor\");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void load(java.util.Properties in, String prefix) {" +
            "  this.mark = in.getProperty(prefix + \".mark\", \"\");" +
            "  try { this.deepest = Integer.parseInt(in.getProperty(prefix + \".deepest\", \"0\")); }" +
            "  catch (Throwable t) { this.deepest = 0; }" +
            "  try { this.when = Long.parseLong(in.getProperty(prefix + \".when\", \"0\")); }" +
            "  catch (Throwable t) { this.when = 0L; }" +
            "  this.kept = \"1\".equals(in.getProperty(prefix + \".kept\", \"0\"));" +
            "  this.crew.clear();" +
            "  String had = in.getProperty(prefix + \".crew\", \"\");" +
            "  if (had.length() > 0) {" +
            "    String[] names = had.split(\"[|]\");" +
            "    int i = 0;" +
            "    while (i < names.length) { this.crew.add(names[i]); i = i + 1; }" +
            "  }" +
            "  this.seen.clear();" +
            "  String trail = in.getProperty(prefix + \".trail\", \"\");" +
            "  if (trail.length() > 0) {" +
            "    String[] ids = trail.split(\"[|]\");" +
            "    int n = 0;" +
            "    while (n < ids.length) {" +
            "      try { this.seen.add(new Integer(Integer.parseInt(ids[n]))); } catch (Throwable t) { }" +
            "      n = n + 1;" +
            "    }" +
            "  }" +
            "  this.paths.clear();" +
            "  String table = in.getProperty(prefix + \".paths\", \"\");" +
            "  if (table.length() > 0) {" +
            "    String[] names = table.split(\"[|]\");" +
            "    int p = 0;" +
            "    while (p < names.length) { this.paths.add(names[p]); p = p + 1; }" +
            "  }" +
            "  this.known.clear();" +
            "  String seenHere = in.getProperty(prefix + \".known\", \"\");" +
            "  if (seenHere.length() > 0) {" +
            "    String[] pairs = seenHere.split(\"[|]\");" +
            "    int q = 0;" +
            "    while (q < pairs.length) {" +
            "      int eq = pairs[q].indexOf('=');" +
            "      try {" +
            "        int code = Integer.parseInt(pairs[q].substring(eq + 1));" +
            "        if (eq > 0 && code / 2 < this.paths.size()) {" +
            "          this.known.put(pairs[q].substring(0, eq), new Integer(code));" +
            "        }" +
            "      } catch (Throwable t) { }" +
            "      q = q + 1;" +
            "    }" +
            "  }" +
            "  this.run.load(in, prefix + \".run\");" +
            "  this.last.load(in, prefix + \".last\");" +
            "  this.floor.load(in, prefix + \".floor\");" +
            "}", c));
    }

    private static void fillRow(Discovery.Discovered d, CtClass c) throws Exception {
        String comp = d.componentClass;
        c.addField(CtField.make("public " + Discovery.LABEL + " rank = null;", c));
        c.addField(CtField.make("public " + Discovery.LABEL + " who = null;", c));
        c.addField(CtField.make("public " + Discovery.LABEL + " much = null;", c));
        c.addField(CtField.make("public " + Discovery.LABEL + " share = null;", c));
        c.addField(CtField.make("public " + BAR + " bar = null;", c));
        c.addField(CtField.make("public " + d.imageClass + " icon = null;", c));

        c.addMethod(CtNewMethod.make(
            "public void room() {" +
            "  " + MAIN + ".align(this.rank, 0);" +
            "  " + MAIN + ".align(this.who, 0);" +
            "  " + MAIN + ".align(this.much, " + RIGHTWARD + ");" +
            "  " + MAIN + ".align(this.share, " + RIGHTWARD + ");" +
            "}", c));

        c.addConstructor(CtNewConstructor.make(
            "public " + ROW_N + "(" + d.glCtxClass + " gl, " + BAR + " bar) {" +
            "  super(gl);" +
            "  this.bar = bar;" +
            "  this.rank = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.rank.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.add(this.rank);" +
            "  this.who = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.who.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.much = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.much.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.share = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.share.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.add(this.who);" +
            "  this.add(this.much);" +
            "  this.add(this.share);" +
            "  this.rank.setFit(" + Discovery.LABEL + "$Fit.valueOf(\"TRUNCATE\"));" +
            "  this.who.setFit(" + Discovery.LABEL + "$Fit.valueOf(\"TRUNCATE\"));" +
            "  this.much.setFit(" + Discovery.LABEL + "$Fit.valueOf(\"TRUNCATE\"));" +
            "  this.share.setFit(" + Discovery.LABEL + "$Fit.valueOf(\"TRUNCATE\"));" +
            "  this.room();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected " + d.dimensionClass + " computePreferredSize(int hintw, int hinth) {" +
            "  return new " + d.dimensionClass + "(" + WIDTH + ", " + LINE + ");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public int wide(" + Discovery.LABEL + " label) { return " + MAIN + ".wide(label); }", c));

        c.addMethod(CtNewMethod.make(
            "public void gauge() {" +
            "  int r = this.wide(this.rank);" +
            "  int n = this.wide(this.who);" +
            "  int m = this.wide(this.much);" +
            "  int p = this.wide(this.share);" +
            "  if (r > " + MAIN + ".colRank) { " + MAIN + ".colRank = r; }" +
            "  if (n > " + MAIN + ".colWho) { " + MAIN + ".colWho = n; }" +
            "  if (m > " + MAIN + ".colMuch) { " + MAIN + ".colMuch = m; }" +
            "  if (p > " + MAIN + ".colShare) { " + MAIN + ".colShare = p; }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected void layout() {" +
            "  int w = this.getWidth();" +
            "  int h = this.getHeight();" +
            "  int rankW = " + SLACK + " + (" + MAIN + ".colRank > " + RANK_W + "" +
            "      ? " + MAIN + ".colRank : " + RANK_W + ");" +
            "  int muchW = " + SLACK + " + (" + MAIN + ".colMuch > 0" +
            "      ? " + MAIN + ".colMuch : " + MUCH_MIN + ");" +
            "  int shareW = " + SLACK + " + (" + MAIN + ".colShare > 0" +
            "      ? " + MAIN + ".colShare : " + SHARE_MIN + ");" +
            "  int from = " + ICON + " + " + TEXT_PAD + ";" +
            "  int shareX = w - " + TEXT_PAD + " - shareW;" +
            "  int muchX = shareX - " + COL_GAP + " - muchW;" +
            "  int nameX = from + rankW;" +
            "  int nameW = " + SLACK + " + " + MAIN + ".colWho;" +
            "  int room = muchX - " + COL_GAP + " - nameX;" +
            "  if (nameW > room) { nameW = room; }" +
            "  if (nameW < 24) { nameW = 24; }" +
            "  this.rank.setBounds(from, " + TEXT_LIFT + ", rankW, h);" +
            "  this.who.setBounds(nameX, " + TEXT_LIFT + ", nameW, h);" +
            "  this.much.setBounds(muchX, " + TEXT_LIFT + ", muchW, h);" +
            "  this.share.setBounds(shareX, " + TEXT_LIFT + ", shareW, h);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected void renderComponent(" + Discovery.RENDERER + " r) {" +
            "  int h = this.getHeight();" +
            "  int from = " + ICON + " + 2;" +
            "  this.bar.render(r, from, 0, this.getWidth() - from, h, this._alpha);" +
            "  if (this.icon != null) {" +
            "    this.icon.render(r, 0, 0, this.icon.getWidth(), this.icon.getHeight()," +
            "        0, (h - " + ICON + ") / 2, " + ICON + ", " + ICON + ", this._alpha);" +
            "  }" +
            "  super.renderComponent(r);" +
            "}", c));

        c.addField(CtField.make("public Object homeRank = null;", c));
        c.addField(CtField.make("public Object homeWho = null;", c));
        c.addField(CtField.make("public Object homeMuch = null;", c));
        c.addField(CtField.make("public Object homeShare = null;", c));

        c.addMethod(CtNewMethod.make(
            "public Object ink(" + Discovery.LABEL + " label, " + Discovery.COLOR4F + " colour," +
            " Object home) {" +
            "  try {" +
            "    java.lang.reflect.Field f = " + MAIN + ".grab(label.getClass(), \"_colors\");" +
            "    if (f == null) { return home; }" +
            "    Object[] now = (Object[]) f.get(label);" +
            "    if (now == null) { return home; }" +
            "    if (home == null) {" +
            "      Object[] kept = new Object[now.length];" +
            "      System.arraycopy(now, 0, kept, 0, now.length);" +
            "      home = kept;" +
            "    }" +
            "    Object[] was = (Object[]) home;" +
            "    int i = 0;" +
            "    while (i < now.length) {" +
            "      now[i] = colour == null ? (i < was.length ? was[i] : now[i]) : colour;" +
            "      i = i + 1;" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "  return home;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void inkRow(" + Discovery.COLOR4F + " colour) {" +
            "  this.room();" +
            "  this.homeRank = this.ink(this.rank, colour, this.homeRank);" +
            "  this.homeWho = this.ink(this.who, colour, this.homeWho);" +
            "  this.homeMuch = this.ink(this.much, colour, this.homeMuch);" +
            "  this.homeShare = this.ink(this.share, colour, this.homeShare);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void setBar(" + Discovery.COLOR4F + " tint) { this.bar.tint = tint; }", c));

        c.addMethod(CtNewMethod.make(
            "public void lay(" + Discovery.LABEL + " label, String text) {" +
            "  String said = " + MAIN + ".padded(text);" +
            "  if (!said.equals(label.getText())) { label.setText(said); }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void show(String place, String name, String figure, String part, float frac," +
            " " + Discovery.COLOR4F + " tint, Object badge) {" +
            "  this.icon = (" + d.imageClass + ") badge;" +
            "  this.lay(this.rank, place);" +
            "  this.lay(this.who, name);" +
            "  this.lay(this.much, figure);" +
            "  this.lay(this.share, part);" +
            "  this.bar.frac = frac;" +
            "}", c));
    }

    private static void fillMouse(Discovery.Discovered d, CtClass c) throws Exception {
        c.addField(CtField.make("public " + METER + " meter = null;", c));
        c.addField(CtField.make("public int grabX = 0;", c));
        c.addField(CtField.make("public int grabY = 0;", c));
        c.addField(CtField.make("public boolean moved = false;", c));

        c.addConstructor(CtNewConstructor.make(
            "public " + MOUSE_N + "(" + METER + " meter) { this.meter = meter; }", c));

        c.addMethod(CtNewMethod.make(
            "public void mousePressed(" + Discovery.MOUSE_EVENT + " e) {" +
            "  e.consume();" +
            "  this.grabX = e.getX() - this.meter.getAbsoluteX();" +
            "  this.grabY = e.getY() - this.meter.getAbsoluteY();" +
            "  this.moved = false;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void mouseReleased(" + Discovery.MOUSE_EVENT + " e) {" +
            "  e.consume();" +
            "  if (this.moved) { " + MAIN + ".store(); }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void mouseEntered(" + Discovery.MOUSE_EVENT + " e) { e.consume(); }", c));

        c.addMethod(CtNewMethod.make(
            "public void mouseDragged(" + Discovery.MOUSE_EVENT + " e) {" +
            "  e.consume();" +
            "  this.moved = true;" +
            "  this.meter.placed = true;" +
            "  int y = e.getY() - this.grabY;" +
            "  int x = e.getX() - this.grabX;" +
            "  this.meter.setLocation(x, y);" +
            "  this.meter.topY = y + this.meter.getHeight();" +
            "  " + d.rootClass + " root = this.meter.getRoot();" +
            "  if (root != null) {" +
            "    " + METER + ".keptRight = root.getDisplayWidth() - x - this.meter.getWidth();" +
            "    " + METER + ".keptDown = root.getDisplayHeight() - this.meter.topY;" +
            "    " + METER + ".keptPlaced = true;" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void mouseClicked(" + Discovery.MOUSE_EVENT + " e) {" +
            "  e.consume();" +
            "  if (this.moved) { return; }" +
            "  int icon = this.meter.iconAt(e.getX() - this.meter.getAbsoluteX()," +
            "      e.getY() - this.meter.getAbsoluteY());" +
            "  if (icon == 3 || icon != 0 && e.getButton() == 0) {" +
            "    if (icon == 1) { " + MAIN + ".reset(); }" +
            "    else { " + MAIN + ".hide(icon == 2); }" +
            "    this.meter.refresh();" +
            "    this.meter.resize();" +
            "    return;" +
            "  }" +
            "  if (e.getButton() == 1) { " + MAIN + ".cycleSegment(); }" +
            "  else if (e.getButton() == 2) {" +
            "    " + MAIN + ".debugPage = (" + MAIN + ".debugPage + 1) % " + DEBUG_PAGES + ";" +
            "    " + MAIN + ".debug = " + MAIN + ".debugPage > 0;" +
            "    " + MAIN + ".store();" +
            "  }" +
            "  else { " + MAIN + ".cycleMode(); }" +
            "  this.meter.refresh();" +
            "}", c));
    }

    private static void meterFields(Discovery.Discovered d, CtClass c) throws Exception {
        c.addField(CtField.make("public " + d.glCtxClass + " gl = null;", c));
        c.addField(CtField.make("public " + Discovery.LABEL + " header = null;", c));
        c.addField(CtField.make("public " + Discovery.LABEL + " footer = null;", c));
        c.addField(CtField.make("public java.util.ArrayList rows = new java.util.ArrayList();", c));
        c.addField(CtField.make("public java.util.ArrayList debugLabels = new java.util.ArrayList();", c));
        c.addField(CtField.make("public int debugShowing = 0;", c));
        c.addField(CtField.make("public " + BAR + " panel = null;", c));
        c.addField(CtField.make("public " + d.imageClass + " art = null;", c));
        c.addField(CtField.make("public int showing = 0;", c));
        c.addField(CtField.make("public boolean placed = false;", c));
        c.addField(CtField.make("public int topY = -1;", c));
        c.addField(CtField.make("public int sawWide = 0;", c));
        c.addField(CtField.make("public int sawTall = 0;", c));
        c.addField(CtField.make("public static int keptRight = -1;", c));
        c.addField(CtField.make("public static int keptDown = -1;", c));
        c.addField(CtField.make("public static boolean keptPlaced = false;", c));
        c.addField(CtField.make("public boolean hover = false;", c));
    }

    private static void fillLedger(Discovery.Discovered d, CtClass c) throws Exception {
        c.addMethod(CtNewMethod.make(
            "public static String knightFile() {" +
            "  return myName().replaceAll(\"[^A-Za-z0-9_-]\", \"_\");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static java.io.File ledger() {" +
            "  String who = knightFile();" +
            "  return new java.io.File(new java.io.File(System.getProperty(\"user.home\"), \"" + LEDGER_DIR + "\")," +
            "      (who.length() == 0 ? \"default\" : who) + \".properties\");" +
            "}", c));

        // older versions saved into the game folder
        c.addMethod(CtNewMethod.make(
            "public static java.io.File oldLedger(String base) {" +
            "  String dir = System.getProperty(\"user.dir\");" +
            "  if (dir == null) { return null; }" +
            "  String who = knightFile();" +
            "  return new java.io.File(dir," +
            "      who.length() == 0 ? base + \".properties\" : base + \"-\" + who + \".properties\");" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void store() {" +
            "  try {" +
            "    java.util.Properties out = new java.util.Properties();" +
            "    out.setProperty(\"window.right\", String.valueOf(" + METER + ".keptRight));" +
            "    out.setProperty(\"window.down\", String.valueOf(" + METER + ".keptDown));" +
            "    out.setProperty(\"window.placed\", " + METER + ".keptPlaced ? \"1\" : \"0\");" +
            "    out.setProperty(\"mode\", String.valueOf(mode));" +
            "    out.setProperty(\"debug\", String.valueOf(debugPage));" +
            "    out.setProperty(\"hidden\", hidden ? \"1\" : \"0\");" +
            "    StringBuffer names = new StringBuffer();" +
            "    java.util.Iterator it = runs.entrySet().iterator();" +
            "    while (it.hasNext()) {" +
            "      java.util.Map$Entry e = (java.util.Map$Entry) it.next();" +
            "      " + RUN + " held = (" + RUN + ") e.getValue();" +
            "      if (names.length() > 0) { names.append('|'); }" +
            "      names.append(held.oid);" +
            "      held.save(out, \"party.\" + held.oid);" +
            "    }" +
            "    out.setProperty(\"parties\", names.toString());" +
            "    java.io.File ledger = ledger();" +
            "    ledger.getParentFile().mkdirs();" +
            "    java.io.File part = new java.io.File(ledger.getPath() + \".part\");" +
            "    java.io.FileOutputStream file = new java.io.FileOutputStream(part);" +
            "    try { out.store(file, \"spiral knights damage meter\"); }" +
            "    finally { file.close(); }" +
            "    if (!part.renameTo(ledger)) {" +
            "      ledger.delete();" +
            "      part.renameTo(ledger);" +
            "    }" +
            "    written = taken_in;" +
            "    memoDirty = false;" +
            "  } catch (Throwable t) { }" +
            "}", c));

        // wipes the floor, last floor and run of the party we're in, whichever is showing
        c.addMethod(CtNewMethod.make(
            "public static void reset() {" +
            "  floorBook.clear();" +
            "  lastBook.clear();" +
            "  runBook.clear();" +
            "  current.kept = true;" +
            "  strayed = 0L;" +
            "  dropped = 0;" +
            "  droppedNote = null;" +
            "  lastHits.clear();" +
            "  taken_in = taken_in + 1;" +
            "  store();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void hide(boolean on) {" +
            "  hidden = on;" +
            "  store();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void restore() {" +
            "  try {" +
            "    java.io.File from = ledger();" +
            "    if (!from.exists()) { from = oldLedger(\"damage-meter\"); }" +
            "    if (from == null || !from.exists()) { from = oldLedger(\"dps-meter\"); }" +
            "    if (from == null || !from.exists()) { return; }" +
            "    java.util.Properties in = new java.util.Properties();" +
            "    java.io.FileInputStream file = new java.io.FileInputStream(from);" +
            "    try { in.load(file); } finally { file.close(); }" +
            "    " + METER + ".keptRight = Integer.parseInt(in.getProperty(\"window.right\", \"-1\"));" +
            "    " + METER + ".keptDown = Integer.parseInt(in.getProperty(\"window.down\", \"-1\"));" +
            "    " + METER + ".keptPlaced = \"1\".equals(in.getProperty(\"window.placed\", \"0\"));" +
            "    mode = Integer.parseInt(in.getProperty(\"mode\", \"0\"));" +
            "    try { debugPage = Integer.parseInt(in.getProperty(\"debug\", \"0\")) % " + DEBUG_PAGES + "; }" +
            "    catch (Throwable t) { debugPage = 0; }" +
            "    if (debugPage < 0) { debugPage = 0; }" +
            "    debug = debugPage > 0;" +
            "    hidden = \"1\".equals(in.getProperty(\"hidden\", \"0\"));" +
            "    runs.clear();" +
            "    String was = in.getProperty(\"parties\", \"\");" +
            "    if (was.length() > 0) {" +
            "      String[] ids = was.split(\"[|]\");" +
            "      int i = 0;" +
            "      while (i < ids.length) {" +
            "        try {" +
            "          int oid = Integer.parseInt(ids[i]);" +
            "          " + RUN + " held = new " + RUN + "();" +
            "          held.oid = oid;" +
            "          held.load(in, \"party.\" + oid);" +
            "          runs.put(new Integer(oid), held);" +
            "        } catch (Throwable t) { }" +
            "        i = i + 1;" +
            "      }" +
            "    }" +
            "    StringBuffer said = new StringBuffer();" +
            "    java.util.Iterator walk = runs.values().iterator();" +
            "    while (walk.hasNext()) {" +
            "      " + RUN + " held = (" + RUN + ") walk.next();" +
            "      said.append(\"  party \").append(held.oid).append(\" in \")" +
            "          .append(held.mark.length() == 0 ? \"nowhere\" : held.mark)" +
            "          .append(\" down to \").append(held.deepest)" +
            "          .append(\", \").append(pretty(held.run.total(0)));" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));
    }

    private static void meterMethods(Discovery.Discovered d, CtClass c) throws Exception {
        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " shade(float r, float g, float b, float a) {" +
            "  return new " + Discovery.COLOR4F + "(r, g, b, a);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " softer(" + Discovery.COLOR4F + " c, float a) {" +
            "  return c == null ? null : new " + Discovery.COLOR4F + "(c.r, c.g, c.b, a);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + Discovery.COLOR4F + " seatColour(int seat) {" +
            "  int at = seat % 8;" +
            "  if (at == 0) { return shade(0.36f, 0.62f, 0.92f, 0.78f); }" +
            "  if (at == 1) { return shade(0.93f, 0.46f, 0.28f, 0.78f); }" +
            "  if (at == 2) { return shade(0.42f, 0.80f, 0.44f, 0.78f); }" +
            "  if (at == 3) { return shade(0.85f, 0.75f, 0.32f, 0.78f); }" +
            "  if (at == 4) { return shade(0.72f, 0.46f, 0.88f, 0.78f); }" +
            "  if (at == 5) { return shade(0.36f, 0.80f, 0.80f, 0.78f); }" +
            "  if (at == 6) { return shade(0.90f, 0.52f, 0.72f, 0.78f); }" +
            "  return shade(0.66f, 0.66f, 0.70f, 0.78f);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static " + d.imageClass + " white(" + d.glCtxClass + " gl) {" +
            "  try {" +
            "    " + d.colorizationClass + "[] plain = new " + d.colorizationClass + "[0];" +
            "    return gl.getImageCache().getImage(\"" + Discovery.WHITE_IMAGE + "\", plain);" +
            "  } catch (Throwable t) { return null; }" +
            "}", c));

        // a clockwise circle arrow as {dx, dy, size} squares around the cell's centre
        c.addField(CtField.make("public static int[] arrow = null;", c));
        c.addMethod(CtNewMethod.make(
            "public static int[] glyph() {" +
            "  if (arrow != null) { return arrow; }" +
            "  java.util.LinkedHashSet dots = new java.util.LinkedHashSet();" +
            "  double rad = 5.5;" +
            "  int a = 90;" +
            "  while (a <= 330) {" +
            "    double t = Math.toRadians((double) a);" +
            "    dots.add(new java.awt.Point((int) Math.round(rad * Math.cos(t)) - 1," +
            "        (int) Math.round(rad * Math.sin(t)) - 1));" +
            "    a = a + 8;" +
            "  }" +
            "  java.util.ArrayList out = new java.util.ArrayList();" +
            "  java.util.Iterator it = dots.iterator();" +
            "  while (it.hasNext()) {" +
            "    java.awt.Point p = (java.awt.Point) it.next();" +
            "    out.add(new int[] { p.x, p.y, 2 });" +
            "  }" +
            "  dots.clear();" +
            "  double t = Math.toRadians(90.0);" +
            "  double ex = rad * Math.cos(t);" +
            "  double ey = rad * Math.sin(t);" +
            "  double along = 0.0;" +
            "  while (along <= 3.5) {" +
            "    double half = 3.0 * (1.0 - along / 3.5);" +
            "    double across = -half;" +
            "    while (across <= half) {" +
            "      double px = ex + Math.sin(t) * along + Math.cos(t) * across;" +
            "      double py = ey - Math.cos(t) * along + Math.sin(t) * across;" +
            "      dots.add(new java.awt.Point((int) Math.floor(px), (int) Math.floor(py)));" +
            "      across = across + 0.5;" +
            "    }" +
            "    along = along + 0.5;" +
            "  }" +
            "  it = dots.iterator();" +
            "  while (it.hasNext()) {" +
            "    java.awt.Point p = (java.awt.Point) it.next();" +
            "    out.add(new int[] { p.x, p.y, 1 });" +
            "  }" +
            "  int[] flat = new int[out.size() * 3];" +
            "  int i = 0;" +
            "  while (i < out.size()) {" +
            "    int[] one = (int[]) out.get(i);" +
            "    flat[i * 3] = one[0];" +
            "    flat[i * 3 + 1] = one[1];" +
            "    flat[i * 3 + 2] = one[2];" +
            "    i = i + 1;" +
            "  }" +
            "  arrow = flat;" +
            "  return flat;" +
            "}", c));

        // a button's backdrop; hands back the ink to draw its glyph in
        c.addMethod(CtNewMethod.make(
            "public " + Discovery.COLOR4F + " cell(" + Discovery.RENDERER + " r, int x, int y, boolean lit) {" +
            "  if (lit) {" +
            "    this.panel.quad(r, x, y, " + BOX + ", " + BOX + ", shade(1.0f, 1.0f, 1.0f, 0.12f), this._alpha);" +
            "    return shade(1.0f, 1.0f, 1.0f, 0.95f);" +
            "  }" +
            "  return shade(0.72f, 0.77f, 0.86f, 0.55f);" +
            "}", c));

        // 1 reset, 2 hide, 3 show (the whole tab while hidden), 0 none
        c.addMethod(CtNewMethod.make(
            "public int iconAt(int x, int y) {" +
            "  if (" + MAIN + ".hidden) {" +
            "    return x >= 0 && y >= 0 && x < this.getWidth() && y < this.getHeight() ? 3 : 0;" +
            "  }" +
            "  int h = this.getHeight();" +
            "  int w = this.getWidth();" +
            "  if (y < h - " + PAD + " - " + BOX + " || y >= h - " + PAD + ") { return 0; }" +
            "  int hx = w - " + PAD + " - " + BOX + ";" +
            "  if (x >= hx && x < hx + " + BOX + ") { return 2; }" +
            "  if (x >= hx - " + BOX + " && x < hx) { return 1; }" +
            "  return 0;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public int pointed() {" +
            "  " + d.rootClass + " root = this.getRoot();" +
            "  if (root == null) { return 0; }" +
            "  return this.iconAt(root.getMouseX() - this.getAbsoluteX()," +
            "      root.getMouseY() - this.getAbsoluteY());" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public int lines() { return this.showing + 1 + this.debugShowing; }", c));

        // containers don't report hits on themselves
        c.addMethod(CtNewMethod.make(
            "public " + d.componentClass + " getHitComponent(int mx, int my) {" +
            "  return this.isVisible() && this.contains(mx, my) ? this : null;" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected " + d.dimensionClass + " computePreferredSize(int hintw, int hinth) {" +
            "  if (" + MAIN + ".hidden) { return new " + d.dimensionClass + "(" + TAB + ", " + TAB + "); }" +
            "  int tall = " + PAD + " * 2 + " + LINE + " + this.lines() * " + LINE + ";" +
            "  return new " + d.dimensionClass + "(" + WIDTH + " + " + PAD + " * 2, tall);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected void layout() {" +
            "  boolean open = !" + MAIN + ".hidden;" +
            "  if (this.header.isVisible() != open) { this.header.setVisible(open); }" +
            "  if (this.footer.isVisible() != open) { this.footer.setVisible(open); }" +
            "  int w = this.getWidth() - " + PAD + " * 2;" +
            "  int top = this.getHeight() - " + PAD + " - " + LINE + ";" +
            "  " + MAIN + ".measurePad(this.header);" +
            "  int edge = " + PAD + " - " + MAIN + ".padW;" +
            "  this.header.setBounds(edge, top + " + TEXT_LIFT + ", w + " + MAIN + ".padW" +
            "      - " + BOX + " * 2, " + LINE + ");" +
            "  int at = 0;" +
            "  while (at < this.rows.size()) {" +
            "    " + ROW + " row = (" + ROW + ") this.rows.get(at);" +
            "    boolean on = open && at < this.showing;" +
            "    if (row.isVisible() != on) { row.setVisible(on); }" +
            "    if (on) {" +
            "      row.setBounds(" + PAD + ", top - (at + 1) * " + LINE + ", w, " + LINE + ");" +
            "    }" +
            "    at = at + 1;" +
            "  }" +
            "  int foot = top - (this.showing + 1) * " + LINE + ";" +
            "  this.footer.setBounds(edge, foot, w + " + MAIN + ".padW, " + LINE + ");" +
            "  int k = 0;" +
            "  while (k < this.debugLabels.size()) {" +
            "    " + Discovery.LABEL + " line = (" + Discovery.LABEL + ") this.debugLabels.get(k);" +
            "    boolean on = open && k < this.debugShowing;" +
            "    if (line.isVisible() != on) { line.setVisible(on); }" +
            "    if (on) {" +
            "      line.setBounds(edge, foot - (k + 1) * " + LINE + ", w + " + MAIN + ".padW," +
            "          " + LINE + ");" +
            "    }" +
            "    k = k + 1;" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "protected void renderComponent(" + Discovery.RENDERER + " r) {" +
            "  int w = this.getWidth();" +
            "  int h = this.getHeight();" +
            "  this.panel.quad(r, 0, 0, w, h, shade(0.04f, 0.05f, 0.07f, 0.78f), this._alpha);" +
            "  int lit = this.pointed();" +
            "  if (" + MAIN + ".hidden) {" +
            "    this.cell(r, " + PAD + ", " + PAD + ", lit == 3);" +
            "    int x = " + PAD + " + (" + BOX + " - 13) / 2;" +
            "    int y = " + PAD + " + (" + BOX + " - 12) / 2;" +
            "    this.panel.quad(r, x, y, 3, 6, seatColour(0), this._alpha);" +
            "    this.panel.quad(r, x + 5, y, 3, 12, seatColour(1), this._alpha);" +
            "    this.panel.quad(r, x + 10, y, 3, 9, seatColour(2), this._alpha);" +
            "    super.renderComponent(r);" +
            "    return;" +
            "  }" +
            "  int cy = h - " + PAD + " - " + BOX + ";" +
            "  int hx = w - " + PAD + " - " + BOX + ";" +
            "  int rx = hx - " + BOX + ";" +
            "  " + Discovery.COLOR4F + " ink = this.cell(r, rx, cy, lit == 1);" +
            "  int[] pts = glyph();" +
            "  int g = 0;" +
            "  while (g < pts.length) {" +
            "    this.panel.quad(r, rx + " + (BOX / 2) + " + pts[g], cy + " + (BOX / 2) + " + pts[g + 1]," +
            "        pts[g + 2], pts[g + 2], ink, this._alpha);" +
            "    g = g + 3;" +
            "  }" +
            "  ink = this.cell(r, hx, cy, lit == 2);" +
            "  this.panel.quad(r, hx + " + (BOX / 2) + " - 5, cy + " + (BOX / 2) + " - 1, 10, 2, ink, this._alpha);" +
            "  this.panel.quad(r, 0, h - 2, w, 2," +
            "      shade(0.55f, 0.62f, 0.75f, 0.30f), this._alpha);" +
            "  int under = h - " + PAD + " - " + LINE + " - 1;" +
            "  this.panel.quad(r, " + PAD + ", under, w - " + PAD + " * 2, 1," +
            "      shade(0.55f, 0.62f, 0.75f, 0.30f), this._alpha);" +
            "  if (this.debugShowing > 0) {" +
            "    int foot = h - " + PAD + " - " + LINE + " - (this.showing + 1) * " + LINE + ";" +
            "    this.panel.quad(r, " + PAD + ", foot, w - " + PAD + " * 2, 1," +
            "        shade(0.55f, 0.62f, 0.75f, 0.30f), this._alpha);" +
            "  }" +
            "  super.renderComponent(r);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void resize() {" +
            "  try {" +
            "    " + d.dimensionClass + " want = this.computePreferredSize(-1, -1);" +
            "    this.setSize(want.width, want.height);" +
            "    " + d.rootClass + " root = this.getRoot();" +
            "    if (root == null) { return; }" +
            "    int wide = root.getDisplayWidth();" +
            "    int tall = root.getDisplayHeight();" +
            "    this.sawWide = wide;" +
            "    this.sawTall = tall;" +
            "    if (!this.placed && keptPlaced) { this.placed = true; }" +
            "    int right = this.placed && keptRight >= 0 ? keptRight : 14;" +
            "    int down = this.placed && keptDown >= 0 ? keptDown : 90;" +
            "    int x = wide - right - want.width;" +
            "    int top = tall - down;" +
            "    if (x + want.width > wide) { x = wide - want.width; }" +
            "    if (x < 0) { x = 0; }" +
            "    if (top > tall) { top = tall; }" +
            "    if (top - want.height < 0) { top = want.height; }" +
            "    this.topY = top;" +
            "    this.setLocation(x, top - want.height);" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void refresh() {" +
            "  try {" +
            "    " + MAIN + ".pump();" +
            "    " + d.rootClass + " over = this.getRoot();" +
            "    if (over != null) {" +
            "      int mx = over.getMouseX();" +
            "      int my = over.getMouseY();" +
            "      this.hover = mx >= this.getAbsoluteX() && my >= this.getAbsoluteY()" +
            "          && mx < this.getAbsoluteX() + this.getWidth()" +
            "          && my < this.getAbsoluteY() + this.getHeight();" +
            "    }" +
            "    " + d.rootClass + " root = this.getRoot();" +
            "    if (root != null && (root.getDisplayWidth() != this.sawWide" +
            "        || root.getDisplayHeight() != this.sawTall)) {" +
            "      this.resize();" +
            "    }" +
            "    " + MAIN + ".align(this.header, 0);" +
            "    " + MAIN + ".align(this.footer, 0);" +
            "    this.header.setText(" + MAIN + ".padded(" + MAIN + ".modeName()" +
            "        + \"  -  \" + " + MAIN + ".spanName()));" +
            "    java.util.ArrayList ids = " + MAIN + ".ranked();" +
            "    int i = 0;" +
            "    int seats = ids.size() < " + SEATS + " ? ids.size() : " + SEATS + ";" +
            "    long best = seats == 0 ? 0L : " + MAIN + ".weight((String) ids.get(0));" +
            "    while (i < seats) {" +
            "      String who = (String) ids.get(i);" +
            "      float frac = best <= 0L || !" + MAIN + ".here(who) ? 0.0f :" +
            "          (((float) " + MAIN + ".weight(who)) / ((float) best));" +
            "      " + Discovery.COLOR4F + " own = (" + Discovery.COLOR4F + ") " + MAIN + ".tintOf(who);" +
            "      if (own == null) { own = seatColour(" + MAIN + ".slotOf(who)); }" +
            "      ((" + ROW + ") this.rows.get(i)).setBar(softer(own, 0.86f));" +
            "      ((" + ROW + ") this.rows.get(i)).inkRow(" + MAIN + ".here(who)" +
            "          ? null : " + MAIN + ".GONE_INK);" +
            "      ((" + ROW + ") this.rows.get(i)).show(" + MAIN + ".here(who)" +
            "          ? ((i + 1) + \".\") : \"" + GONE_MARK + "\", who," +
            "          " + MAIN + ".figure(who), " + MAIN + ".share(who), frac, own," +
            "          " + MAIN + ".badgeOf(who));" +
            "      ((" + ROW + ") this.rows.get(i)).gauge();" +
            "      i = i + 1;" +
            "    }" +
            "    int lit = this.hover ? this.pointed() : 0;" +
            "    if (lit == 1) {" +
            "      this.footer.setText(" + MAIN + ".padded(\"reset: start the whole run over\"));" +
            "    } else if (lit == 2) {" +
            "      this.footer.setText(" + MAIN + ".padded(\"hide the meter (it keeps counting)\"));" +
            "    } else if (this.hover) {" +
            "      this.footer.setText(" + MAIN + ".padded(\"left: \" + " + MAIN + ".nextMode()" +
            "          + \"      right: \" + " + MAIN + ".nextSpan()));" +
            "    } else {" +
            "      long total = " + MAIN + ".book().total(" + MAIN + ".kind());" +
            "      this.footer.setText(" + MAIN + ".padded(\"total  \"" +
            "          + " + MAIN + ".precise(total)));" +
            "    }" +
            "    java.util.ArrayList said = " + MAIN + ".debug ? " + MAIN + ".debugLines()" +
            "        : new java.util.ArrayList();" +
            "    int lines = said.size() < " + DEBUG_SLOTS + " ? said.size() : " + DEBUG_SLOTS + ";" +
            "    int k = 0;" +
            "    while (k < lines) {" +
            "      " + Discovery.LABEL + " line = (" + Discovery.LABEL + ") this.debugLabels.get(k);" +
            "      " + MAIN + ".align(line, 0);" +
            "      String text = " + MAIN + ".padded((String) said.get(k));" +
            "      if (!text.equals(line.getText())) { line.setText(text); }" +
            "      k = k + 1;" +
            "    }" +
            "    if (seats != this.showing || lines != this.debugShowing) {" +
            "      this.showing = seats;" +
            "      this.debugShowing = lines;" +
            "      this.resize();" +
            "    }" +
            "    this.invalidate();" +
            "  } catch (Throwable t) { }" +
            "}", c));
    }

    private static void meterCtor(Discovery.Discovered d, CtClass c) throws Exception {
        c.addConstructor(CtNewConstructor.make(
            "public " + METER_N + "(" + d.glCtxClass + " gl) {" +
            "  super(gl, null);" +
            "  this.gl = gl;" +
            "  this.art = white(gl);" +
            "  this.panel = new " + BAR + "(this.art, null, shade(0.0f, 0.0f, 0.0f, 0.62f));" +
            "  int i = 0;" +
            "  this.header = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.header.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.add(this.header);" +
            "  this.footer = new " + Discovery.LABEL + "(gl, \"\");" +
            "  this.footer.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "  this.add(this.footer);" +
            "  " + MAIN + ".align(this.header, 0);" +
            "  " + MAIN + ".align(this.footer, 0);" +
            "  int k = 0;" +
            "  while (k < " + DEBUG_SLOTS + ") {" +
            "    " + Discovery.LABEL + " line = new " + Discovery.LABEL + "(gl, \"\");" +
            "    line.setStyleConfig(\"" + Discovery.TEXT_STYLE + "\");" +
            "    line.setFit(" + Discovery.LABEL + "$Fit.valueOf(\"TRUNCATE\"));" +
            "    line.setVisible(false);" +
            "    this.debugLabels.add(line);" +
            "    this.add(line);" +
            "    k = k + 1;" +
            "  }" +
            "  while (i < " + SEATS + ") {" +
            "    " + ROW + " row = new " + ROW + "(gl," +
            "        new " + BAR + "(this.art, seatColour(i), shade(1.0f, 1.0f, 1.0f, 0.08f)));" +
            "    this.rows.add(row);" +
            "    this.add(row);" +
            "    i = i + 1;" +
            "  }" +
            "  this.addListener(new " + MOUSE + "(this));" +
            "}", c));
    }

    private static void fillWindowGlue(Discovery.Discovered d, CtClass c) throws Exception {
        // in mission lobbies the scene's click catcher lands over the meter and takes its clicks;
        // only while the mouse is on the meter, lift it just above whatever is taking them
        c.addMethod(CtNewMethod.make(
            "public static void unblock() {" +
            "  try {" +
            "    if (meter == null) { return; }" +
            "    " + d.windowClass + " mine = (" + d.windowClass + ") meter;" +
            "    " + d.rootClass + " root = mine.getRoot();" +
            "    if (root == null) { return; }" +
            "    int mx = root.getMouseX();" +
            "    int my = root.getMouseY();" +
            "    if (mine.getHitComponent(mx, my) == null) { return; }" +
            "    if (fHover == null) { fHover = grab(root.getClass(), \"_hcomponent\"); }" +
            "    Object over = fHover.get(root);" +
            "    if (over == null) { return; }" +
            // getWindow() is protected on components
            "    if (mWindowOf == null) {" +
            "      mWindowOf = Class.forName(\"" + d.componentClass + "\").getDeclaredMethod(\"getWindow\", new Class[0]);" +
            "      mWindowOf.setAccessible(true);" +
            "    }" +
            "    " + d.windowClass + " theirs = (" + d.windowClass + ") mWindowOf.invoke(over, new Object[0]);" +
            "    if (theirs == null || theirs == mine) { return; }" +
            // the scene view's own click catcher is a full-screen modal on our layer; we sit
            // above it on most floors, but lobbies add it after the meter. real dialogs aren't
            // full-screen, and get their clicks through a child component
            "    boolean world = over == theirs" +
            "        && theirs.getWidth() >= root.getDisplayWidth()" +
            "        && theirs.getHeight() >= root.getDisplayHeight();" +
            "    if (theirs.isModal() && !world || theirs.getLayer() != mine.getLayer()) {" +
            "      return;" +
            "    }" +
            "    if (fWindows == null) { fWindows = grab(root.getClass(), \"_windows\"); }" +
            "    java.util.List all = (java.util.List) fWindows.get(root);" +
            "    int at = all.indexOf(mine);" +
            "    int to = all.indexOf(theirs);" +
            "    if (at < 0 || to <= at) { return; }" +
            "    all.remove(at);" +
            "    all.add(to, mine);" +
            "    if (mHover == null) {" +
            "      Class on = root.getClass();" +
            "      while (on != null && mHover == null) {" +
            "        try {" +
            "          mHover = on.getDeclaredMethod(\"updateHoverComponent\"," +
            "              new Class[] { Integer.TYPE, Integer.TYPE });" +
            "          mHover.setAccessible(true);" +
            "        } catch (NoSuchMethodException e) { on = on.getSuperclass(); }" +
            "      }" +
            "    }" +
            "    if (mHover != null) {" +
            "      mHover.invoke(root, new Object[] { new Integer(mx), new Integer(my) });" +
            "    }" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void open() {" +
            "  try {" +
            "    if (meter != null || ctx == null) { return; }" +
            "    " + d.rootClass + " root = ((" + Discovery.CTX + ") ctx).getRoot();" +
            "    if (root == null) { return; }" +
            "    " + METER + " made = new " + METER + "((" + d.glCtxClass + ") ctx);" +
            "    meter = made;" +
            "    root.addWindow(made);" +
            "    made.resize();" +
            "  } catch (Throwable t) {" +
            "  }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void beat() {" +
            "  try {" +
            "    pump();" +
            "    learn();" +
            "    if (taken_in != written || memoDirty) { store(); }" +
            "    if (party == null || place() == null) { close(); return; }" +
            "    Object now = boss == null ? null" +
            "        : ((" + d.dirBaseClass + ") boss)." + d.currentObjMethod + "();" +
            "    if (now == null) {" +
            // only forget the party after the handover, but stop showing it straight away
            "      if (meter != null && ((" + d.windowClass + ") meter).isVisible()) {" +
            "        ((" + d.windowClass + ") meter).setVisible(false);" +
            "      }" +
            "      if (parted == 0L) { parted = System.currentTimeMillis(); }" +
            "      if (System.currentTimeMillis() - parted > " + HANDOVER + ") { leave(); }" +
            "      return;" +
            "    }" +
            "    parted = 0L;" +
            "    if (now != party && System.currentTimeMillis() - stirred > " + QUIET + ") {" +
            "      close();" +
            "      return;" +
            "    }" +
            "    away = 0.0f;" +
            "    if (meter != null && !((" + d.windowClass + ") meter).isAdded()) { meter = null; }" +
            "    if (meter == null) { open(); }" +
            "    if (meter != null && !((" + d.windowClass + ") meter).isVisible()) {" +
            "      ((" + d.windowClass + ") meter).setVisible(true);" +
            "    }" +
            "    if (meter != null) { ((" + METER + ") meter).refresh(); }" +
            "    unblock();" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void wake(Object client) {" +
            "  try {" +
            "    if (ctx == null) { ctx = client; }" +
            "    if (pacer != null) { return; }" +
            "    " + d.rootClass + " root = ((" + Discovery.CTX + ") ctx).getRoot();" +
            "    if (root == null) { return; }" +
            "    Object made = new " + WATCH + "();" +
            "    root.addTickParticipant((" + d.tickableIface + ") made);" +
            "    pacer = made;" +
            "  } catch (Throwable t) { }" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void everyBeat() {" +
            "  indexStep();" +
            "  findHeads();" +
            "  beat();" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public static void attach(Object client, Object object, Object director) {" +
            "  try {" +
            "    if (party == object) { return; }" +
            "    drop();" +
            "    ctx = client;" +
            "    party = object;" +
            "    boss = director;" +
            "    parted = 0L;" +
            "    seeing = null;" +
            "    looked = false;" +
            "    if (!loaded) { loaded = true; restore(); }" +
            "    noteRun(false);" +
            "    strayed = 0L;" +
            "    away = 0.0f;" +
            "    depthSeen = -1;" +
            "    segment = 0;" +
            "    Object made = pacer != null ? pacer : new " + WATCH + "();" +
            "    ((" + Discovery.DOBJECT + ") object)." + d.addListenerMethod +
                  "((" + d.listenerBaseIface + ") made, true);" +
            "    watch = made;" +
            "    store();" +
            "    open();" +
            "  } catch (Throwable t) { }" +
            "}", c));
    }

    private static void watchFields(CtClass c) throws Exception {
        c.addField(CtField.make("public float since = 1.0f;", c));
        c.addConstructor(CtNewConstructor.make("public " + WATCH_N + "() { }", c));
    }

    private static void fillWatch(Discovery.Discovered d, CtClass c) throws Exception {
        c.addMethod(CtNewMethod.make(
            "public void " + d.damageMethod + "(" + Discovery.DAMAGE_EVENT + " e) {" +
            "  " + MAIN + ".record(e);" +
            "}", c));

        c.addMethod(CtNewMethod.make(
            "public void tick(float dt) {" +
            "  " + MAIN + ".watchHeads();" +
            "  " + MAIN + ".sweep();" +
            "  this.since = this.since + dt;" +
            "  if (this.since < 0.25f) { return; }" +
            "  this.since = 0.0f;" +
            "  " + MAIN + ".everyBeat();" +
            "}", c));
    }

    private static void hookDirector(Discovery.Discovered d, CtClass dirCt) throws Exception {
        // several methods are called "a"; pick by signature
        dirCt.getMethod(d.objectReadyMethod, d.objectReadyDesc).insertAfter(
            "try { " + MAIN + ".wake(this." + d.dirCtxField + "); } catch (Throwable t) { }"
                + "if ($1 instanceof " + Discovery.PARTY_OBJECT + ") {"
                + " try { " + MAIN + ".attach(this." + d.dirCtxField + ", $1, this); }"
                + " catch (Throwable t) { } }");
    }
}
