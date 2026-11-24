package com.atakmap.android.autotak;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;

import com.atakmap.android.autotak.plugin.BuildConfig;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.icons.UserIcon;
import com.atakmap.android.icons.UserIconDatabase;
import com.atakmap.android.icons.UserIconSet;
import com.atakmap.android.importexport.CotEventFactory;
import com.atakmap.android.layers.LayersMapComponent;
import com.atakmap.android.maps.CardLayer;
import com.atakmap.map.layer.raster.RasterDataStore;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.map.layer.Layer;
import com.atakmap.map.layer.raster.RasterLayer2;
import android.os.ParcelFileDescriptor;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapGroup;
import com.atakmap.android.maps.MapItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.maps.Marker;
import com.atakmap.android.maps.PointMapItem;
import com.atakmap.android.preference.AtakPreferences;
import com.atakmap.comms.TAKServerListener;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;
import com.atakmap.coremap.maps.coords.GeoPoint;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.autotak.companion.ICompanionHost;
import dev.autotak.companion.ICompanionPlugin;

/**
 * Łącze z aplikacją AutoTAK: podłącza się do jej usługi, przesyła pełny obraz mapy ATAK jako CoT,
 * a potem same zmiany. Dane nie wychodzą poza telefon. Treści obiektów nie trafiają do logów.
 */
public class CompanionLink implements ServiceConnection,
        MapEventDispatcher.MapEventDispatchListener {

    private static final String TAG = "AutoTAKLink";

    /** Najwyższa wersja protokołu obsługiwana przez wtyczkę (2: aplikacja podaje swoją wersję, 3: wybór obiektu w ATAK trafia do aplikacji). */
    static final int PROTOCOL = 3;

    /** Pakiety aplikacji AutoTAK: wydanie i kompilacja testowa. */
    private static final String[] HOSTS = {
            "com.pnh.autotak", "com.pnh.autotak.debug"
    };
    private static final String HOST_SERVICE = "dev.autotak.app.companion.CompanionService";

    /** Paczka CoT mieści się z zapasem w limicie jednej transakcji Bindera (1 MB). */
    private static final int BATCH_CHARS = 100_000;
    private static final long RETRY_MS = 15_000;
    private static final long SWEEP_MS = 15_000;
    private static final long STATE_MS = 250;
    private static final long FLUSH_MS = 200;
    private static final long MAX_MAP_XML = 64 * 1024;

    private static final int FLAG_SNAPSHOT_START = 1;
    private static final int FLAG_SNAPSHOT_END = 2;

    /** Czasy zdarzenia zmieniają się przy każdym zapisie do CoT, więc nie liczą się jako zmiana. */
    private static final Pattern TIMES = Pattern.compile(" (time|start|stale)=['\"][^'\"]*['\"]");

    private static final String[] EVENTS = {
            MapEvent.ITEM_ADDED, MapEvent.ITEM_REMOVED, MapEvent.ITEM_REFRESH,
            MapEvent.ITEM_PERSIST, MapEvent.ITEM_DRAG_DROPPED
    };

    static final String TEST_INJECT = "com.atakmap.android.autotak.TEST_INJECT";

    private final MapView mapView;
    private final Context context;
    private HandlerThread thread;
    private Handler worker;

    private volatile ICompanionHost host;
    private boolean bound;
    private int hostIndex = -1;
    /** Stan łącza dla panelu wtyczki. */
    public static final int STATE_WAITING = 0;
    public static final int STATE_CONNECTED = 1;
    public static final int STATE_REFUSED = 2;
    public static final int STATE_LOST = 3;

    private volatile int state = STATE_WAITING;
    private volatile int protocol;
    private volatile int connectedHost = -1;
    private volatile int sentCount;

    public int getState() {
        return state;
    }

    public boolean isLinked() {
        return host != null;
    }

    public int getProtocol() {
        return protocol;
    }

    /** Numer aplikacji po drugiej stronie: 0 — wydanie, 1 — wersja testowa, -1 — brak. */
    public int getConnectedHost() {
        return connectedHost;
    }

    /** Wersja połączonej aplikacji AutoTAK; null, gdy aplikacja jest starsza i jej nie podaje (2.1.0 lub wcześniejsza). */
    public String getHostVersion() {
        return hostVersion;
    }

    private volatile String hostVersion;

    public int getSentCount() {
        return sentCount;
    }

    public SharedPreferences getPrefs() {
        return prefs;
    }

    /** „Wymuś synchronizację”: konfiguracja, stan i pełny obraz od nowa; bez łącza — próba połączenia. */
    public void forceSync() {
        PluginLog.add("Forced synchronization requested");
        worker.post(() -> {
            if (host == null) {
                worker.removeCallbacks(bindTask);
                hostIndex = -1;
                tryBind();
                return;
            }
            lastConfig = "";
            lastState = "";
            sendConfig();
            snapshot();
            worker.removeCallbacks(stateTask);
            worker.post(stateTask);
        });
    }

    /** UID obiektu → skrót ostatnio wysłanej treści. Tylko wątek roboczy. */
    private final Map<String, Integer> sent = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private boolean flushScheduled;
    private String lastState = "";
    /** Czy ATAK podaje prędkość i kierunek — do dziennika, zapisywane przy zmianie (bez pozycji). */
    private String lastMotion = "";

    private final Map<String, Integer> eventCounts = new HashMap<>();

    /**
     * ATAK nie zgłasza zdarzeniem mapy przesunięcia obiektu (np. kontaktu po wiadomości z sieci),
     * więc każdy obiekt punktowy ma własny nasłuch. Tylko wątek roboczy.
     */
    private final Map<String, PointMapItem> watched = new HashMap<>();
    private final PointMapItem.OnPointChangedListener pointListener =
            new PointMapItem.OnPointChangedListener() {
                @Override
                public void onPointChanged(PointMapItem item) {
                    final String uid = item.getUID();
                    worker.post(() -> markDirty(uid));
                }
            };
    private BroadcastReceiver testReceiver;
    private volatile double[] testSelf;
    private volatile String testCallsign;

    // ---- Wybór obiektu w ATAK → AutoTAK (protokół 3) ------------------------------------------

    private static final String SHOW_DETAILS = "com.atakmap.android.maps.SHOW_DETAILS";
    private static final String HIDE_DETAILS = "com.atakmap.android.maps.HIDE_DETAILS";
    private static final long ECHO_MS = 2_000;

    /** Ostatni wybór, który przyszedł z AutoTAK — żeby nie odsyłać go z powrotem. */
    private volatile String echoUid;
    private volatile long echoUntil;
    /** Ostatni wybór zgłoszony do AutoTAK. Tylko wątek roboczy. */
    private String reportedUid;

    private final BroadcastReceiver selectionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            final String uid = SHOW_DETAILS.equals(intent.getAction()) ? intent.getStringExtra("uid") : null;
            if (SHOW_DETAILS.equals(intent.getAction()) && uid == null)
                return;
            // Własna reakcja na wybór z AutoTAK też wywołuje te rozgłoszenia — pomijamy je.
            if (SystemClock.elapsedRealtime() < echoUntil && (uid == null || uid.equals(echoUid)))
                return;
            // Przy wyborze obiektu ATAK najpierw ukrywa poprzednie okno, a zaraz potem pokazuje nowe —
            // zdjęcie wyboru zgłaszamy więc z małym opóźnieniem i tylko wtedy, gdy nie przyszedł nowy wybór.
            worker.removeCallbacks(clearSelection);
            if (uid == null)
                worker.postDelayed(clearSelection, 400);
            else
                worker.post(() -> reportSelection(uid));
        }
    };

    private final Runnable clearSelection = new Runnable() {
        @Override
        public void run() {
            reportSelection(null);
        }
    };

    private void reportSelection(String uid) {
        final ICompanionHost h = host;
        if (h == null || protocol < 3)
            return;
        if (uid == null ? reportedUid == null : uid.equals(reportedUid))
            return;
        // Obiektu, którego AutoTAK nie dostał (np. przy „Tylko kontakty”), nie zgłaszamy.
        if (uid != null && !sent.containsKey(uid))
            return;
        reportedUid = uid;
        try {
            h.selected(uid);
        } catch (RemoteException e) {
            host = null;
        }
    }

    // ---- Ustawienia wtyczki (Tool Preferences w ATAK) ----------------------------------------

    static final String PREF_SELECT = "autotak.select_action";
    static final String PREF_OBJECTS = "autotak.objects";
    static final String PREF_SEND_SELF = "autotak.send_self";
    static final String PREF_SEND_ICONS = "autotak.send_icons";
    static final String PREF_SEND_MAPS = "autotak.send_maps";
    static final String PREF_SEND_ACTIVE_MAP = "autotak.send_active_map";

    private final AtakPreferences atakPrefs;
    private final SharedPreferences prefs;

    /** Zmiana ustawienia działa od razu: konfiguracja, stan i pełny obraz idą od nowa. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                    if (key == null || !key.startsWith("autotak."))
                        return;
                    Log.d(TAG, "setting changed: " + key);
                    PluginLog.add("Setting changed: " + key + " = " + p.getAll().get(key));
                    worker.post(() -> {
                        if (host == null)
                            return;
                        lastConfig = "";
                        lastState = "";
                        sendConfig();
                        snapshot();
                    });
                }
            };

    private boolean contactsOnly() {
        return "contacts".equals(prefs.getString(PREF_OBJECTS, "all"));
    }

    public CompanionLink(MapView mapView) {
        this.mapView = mapView;
        this.context = mapView.getContext();
        // Ustawienia ATAK i wtyczek: ATAK trzyma je we własnym magazynie, nie w domyślnym pliku aplikacji.
        this.atakPrefs = AtakPreferences.getInstance(context);
        this.prefs = atakPrefs.getSharedPrefs();
    }

    public void start() {
        thread = new HandlerThread("AutoTAK-link");
        thread.start();
        worker = new Handler(thread.getLooper());
        for (String type : EVENTS)
            mapView.getMapEventDispatcher().addMapEventListener(type, this);
        // Wybór obiektu w ATAK: ATAK ogłasza go rozgłoszeniem okna informacji, a zdjęcie wyboru — jego ukryciem.
        DocumentedIntentFilter selection = new DocumentedIntentFilter();
        selection.addAction(SHOW_DETAILS);
        selection.addAction(HIDE_DETAILS);
        AtakBroadcast.getInstance().registerReceiver(selectionReceiver, selection);
        PluginLog.init(context);
        PluginLog.add("Plugin started: version " + BuildConfig.VERSION_NAME + ", ATAK " + atakVersion());
        if (BuildConfig.DEBUG)
            registerTestReceiver();
        atakPrefs.registerListener(prefListener);
        worker.post(bindTask);
    }

    public void stop() {
        for (String type : EVENTS)
            mapView.getMapEventDispatcher().removeMapEventListener(type, this);
        AtakBroadcast.getInstance().unregisterReceiver(selectionReceiver);
        atakPrefs.unregisterListener(prefListener);
        if (testReceiver != null)
            context.unregisterReceiver(testReceiver);
        final Handler w = worker;
        w.post(() -> {
            w.removeCallbacksAndMessages(null);
            for (PointMapItem item : watched.values())
                item.removeOnPointChangedListener(pointListener);
            watched.clear();
            host = null;
            unbind();
            thread.quitSafely();
        });
    }

    // ---- Podłączanie -----------------------------------------------------------------------

    /** Jedno zadanie podłączania — żeby dało się je zdjąć z kolejki. */
    private final Runnable bindTask = new Runnable() {
        @Override
        public void run() {
            tryBind();
        }
    };

    private void tryBind() {
        if (host != null || bound)
            return;
        for (int i = hostIndex + 1; i < HOSTS.length; i++) {
            Intent intent = new Intent().setComponent(new ComponentName(HOSTS[i], HOST_SERVICE));
            boolean ok = false;
            try {
                ok = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
            } catch (SecurityException e) {
                Log.w(TAG, "bind refused for host " + i);
            }
            if (ok) {
                bound = true;
                hostIndex = i;
                Log.i(TAG, "bind started, host " + i);
                return;
            }
            try {
                context.unbindService(this);
            } catch (IllegalArgumentException ignored) {
            }
        }
        hostIndex = -1;
        if (state != STATE_REFUSED)
            state = STATE_WAITING;
        connectedHost = -1;
        worker.removeCallbacks(bindTask);
        worker.postDelayed(bindTask, RETRY_MS);
    }

    private void unbind() {
        if (!bound)
            return;
        bound = false;
        try {
            context.unbindService(this);
        } catch (IllegalArgumentException ignored) {
        }
    }

    /** Po odmowie albo zerwaniu: próba z kolejną aplikacją, a po przejściu listy — od początku. */
    private void nextHost() {
        host = null;
        unbind();
        worker.removeCallbacks(bindTask);
        worker.post(bindTask);
    }

    @Override
    public void onServiceConnected(ComponentName name, final IBinder binder) {
        worker.post(() -> connected(binder));
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        worker.post(() -> {
            host = null;
            state = STATE_LOST;
            connectedHost = -1;
            PluginLog.add("Connection to AutoTAK lost");
        });
    }

    @Override
    public void onBindingDied(ComponentName name) {
        worker.post(() -> {
            hostIndex = -1;
            nextHost();
        });
    }

    @Override
    public void onNullBinding(ComponentName name) {
        worker.post(this::nextHost);
    }

    private void connected(IBinder binder) {
        ICompanionHost candidate = ICompanionHost.Stub.asInterface(binder);
        try {
            int accepted = candidate.hello(PROTOCOL, BuildConfig.VERSION_NAME, atakVersion(), plugin);
            if (accepted <= 0) {
                Log.i(TAG, "host " + hostIndex + " refused");
                state = STATE_REFUSED;
                PluginLog.add("Refused by " + HOSTS[hostIndex] + " (not in linked mode or this ATAK is not trusted)");
                nextHost();
                return;
            }
            host = candidate;
            state = STATE_CONNECTED;
            protocol = accepted;
            connectedHost = hostIndex;
            // Wersję aplikacji podaje dopiero protokół 2; starszej aplikacji o nią nie pytamy.
            String version = null;
            if (accepted >= 2) {
                try {
                    JSONObject info = new JSONObject(candidate.hostInfo());
                    version = info.optString("app") + " (" + info.optString("build") + ")";
                } catch (Exception e) {
                    Log.w(TAG, "host info failed: " + e.getClass().getSimpleName());
                }
            }
            hostVersion = version;
            PluginLog.add("Connected to " + HOSTS[hostIndex] + ", protocol " + accepted + ", app "
                    + (version == null ? "2.1.0 or earlier" : version));
            Log.i(TAG, "connected, protocol " + accepted);
            lastConfig = "";
            lastState = "";
            sendConfig();
            snapshot();
            worker.removeCallbacks(sweepTask);
            worker.removeCallbacks(stateTask);
            worker.postDelayed(sweepTask, SWEEP_MS);
            worker.post(stateTask);
        } catch (RemoteException e) {
            host = null;
        }
    }

    private String atakVersion() {
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    // ---- Obraz sytuacji --------------------------------------------------------------------

    private List<MapItem> allItems() {
        final List<MapItem> items = new ArrayList<>();
        mapView.getRootGroup().deepForEachItem(new MapGroup.MapItemsCallback() {
            @Override
            public boolean onItemFunction(MapItem item) {
                items.add(item);
                return false;
            }
        });
        return items;
    }

    /** CoT obiektu albo null, gdy obiekt nie ma zapisu CoT albo jest własną pozycją. */
    private String toCot(MapItem item) {
        if (item == null || item.getUID().equals(MapView.getDeviceUid()))
            return null;
        try {
            // „Tylko kontakty”: obiekt z danymi zespołu albo typowy raport pozycji użytkownika —
            // ta sama reguła, którą aplikacja AutoTAK rozpoznaje kontakt.
            if (contactsOnly() && item.getMetaString("team", null) == null
                    && !"a-f-G-U-C".equals(item.getType()))
                return null;
            CotEvent event = CotEventFactory.createCotEvent(item);
            if (event == null || !event.isValid())
                return null;
            enrich(item, event);
            return event.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ATAK trzyma zespół, rolę, telefon i e-mail kontaktu w danych obiektu, ale nie wpisuje ich
     * do CoT tworzonego z obiektu — dopisujemy je tak, jak wyglądają w wiadomości z sieci.
     */
    private static void enrich(MapItem item, CotEvent event) {
        String team = item.getMetaString("team", null);
        String phone = item.getMetaString("phoneNumber", null);
        String email = item.getMetaString("emailAddress", null);
        if (team == null && phone == null && email == null)
            return;
        CotDetail detail = event.getDetail();
        if (detail == null) {
            detail = new CotDetail("detail");
            event.setDetail(detail);
        }
        if (team != null && detail.getFirstChildByName(0, "__group") == null) {
            CotDetail group = new CotDetail("__group");
            group.setAttribute("name", team);
            String role = item.getMetaString("atakRoleType", null);
            if (role != null)
                group.setAttribute("role", role);
            detail.addChild(group);
        }
        if (phone != null || email != null) {
            CotDetail contact = detail.getFirstChildByName(0, "contact");
            if (contact == null) {
                contact = new CotDetail("contact");
                detail.addChild(contact);
            }
            if (phone != null && contact.getAttribute("phone") == null)
                contact.setAttribute("phone", phone);
            if (email != null && contact.getAttribute("emailAddress") == null)
                contact.setAttribute("emailAddress", email);
        }
    }

    private static int fingerprint(String cot) {
        return TIMES.matcher(cot).replaceAll("").hashCode();
    }

    /** Pełny obraz: wszystkie obiekty w paczkach, pierwsza i ostatnia paczka oznaczone. */
    private void snapshot() {
        final ICompanionHost h = host;
        if (h == null)
            return;
        long t0 = SystemClock.elapsedRealtime();
        List<MapItem> items = allItems();
        long t1 = SystemClock.elapsedRealtime();
        sent.clear();
        List<String> batch = new ArrayList<>();
        int chars = 0, total = 0, totalChars = 0, batches = 0;
        boolean first = true;
        try {
            for (MapItem item : items) {
                String cot = toCot(item);
                if (cot == null)
                    continue;
                watch(item);
                sent.put(item.getUID(), fingerprint(cot));
                batch.add(cot);
                chars += cot.length();
                total++;
                totalChars += cot.length();
                if (chars >= BATCH_CHARS) {
                    h.events(batch, first ? FLAG_SNAPSHOT_START : 0);
                    first = false;
                    batches++;
                    batch = new ArrayList<>();
                    chars = 0;
                }
            }
            h.events(batch, (first ? FLAG_SNAPSHOT_START : 0) | FLAG_SNAPSHOT_END);
            batches++;
        } catch (RemoteException e) {
            host = null;
            return;
        }
        sentCount = sent.size();
        PluginLog.add("Full picture sent: " + total + " items" + (contactsOnly() ? " (contacts only)" : ""));
        Log.i(TAG, "snapshot: items on map " + items.size() + ", sent " + total + ", chars "
                + totalChars + ", batches " + batches + ", list " + (t1 - t0) + " ms, total "
                + (SystemClock.elapsedRealtime() - t0) + " ms");
    }

    @Override
    public void onMapEvent(MapEvent event) {
        final MapItem item = event.getItem();
        if (item == null)
            return;
        final String type = event.getType();
        final String uid = item.getUID();
        worker.post(() -> {
            Integer n = eventCounts.get(type);
            eventCounts.put(type, n == null ? 1 : n + 1);
            if (MapEvent.ITEM_ADDED.equals(type))
                watch(item);
            else if (MapEvent.ITEM_REMOVED.equals(type))
                unwatch(uid);
            markDirty(uid);
        });
    }

    private void markDirty(String uid) {
        if (host == null)
            return;
        dirty.add(uid);
        if (!flushScheduled) {
            flushScheduled = true;
            worker.postDelayed(this::flush, FLUSH_MS);
        }
    }

    private void watch(MapItem item) {
        if (!(item instanceof PointMapItem) || watched.containsKey(item.getUID()))
            return;
        watched.put(item.getUID(), (PointMapItem) item);
        ((PointMapItem) item).addOnPointChangedListener(pointListener);
    }

    private void unwatch(String uid) {
        PointMapItem item = watched.remove(uid);
        if (item != null)
            item.removeOnPointChangedListener(pointListener);
    }

    /** Wysyła obiekty zgłoszone przez zdarzenia mapy; brak obiektu na mapie oznacza usunięcie. */
    private void flush() {
        flushScheduled = false;
        final ICompanionHost h = host;
        if (h == null || dirty.isEmpty())
            return;
        List<String> changed = new ArrayList<>();
        List<String> gone = new ArrayList<>();
        for (String uid : dirty) {
            MapItem item = mapView.getRootGroup().deepFindUID(uid);
            String cot = item == null ? null : toCot(item);
            if (cot == null) {
                unwatch(uid);
                if (sent.remove(uid) != null)
                    gone.add(uid);
                continue;
            }
            int print = fingerprint(cot);
            Integer before = sent.get(uid);
            if (before == null || before != print) {
                sent.put(uid, print);
                changed.add(cot);
            }
        }
        dirty.clear();
        sentCount = sent.size();
        send(h, changed, gone);
    }

    private void send(ICompanionHost h, List<String> changed, List<String> gone) {
        try {
            List<String> batch = new ArrayList<>();
            int chars = 0;
            for (String cot : changed) {
                batch.add(cot);
                chars += cot.length();
                if (chars >= BATCH_CHARS) {
                    h.events(batch, 0);
                    batch = new ArrayList<>();
                    chars = 0;
                }
            }
            if (!batch.isEmpty())
                h.events(batch, 0);
            if (!gone.isEmpty())
                h.removed(gone);
        } catch (RemoteException e) {
            host = null;
        }
    }

    /**
     * Okresowe porównanie całej mapy z tym, co wysłano: łapie zmiany, których ATAK nie zgłasza
     * zdarzeniem (np. przesunięcie kontaktu przez wiadomość z sieci).
     */
    private final Runnable sweepTask = new Runnable() {
        @Override
        public void run() {
            final ICompanionHost h = host;
            if (h == null)
                return;
            // Ponowne powitanie: AutoTAK mógł wyjść z trybu powiązanego — wtedy odmawia i kończymy.
            try {
                if (h.hello(PROTOCOL, BuildConfig.VERSION_NAME, atakVersion(), plugin) <= 0) {
                    Log.i(TAG, "host left linked mode");
                    state = STATE_REFUSED;
                    PluginLog.add("AutoTAK left linked mode");
                    connectedHost = -1;
                    hostIndex = -1;
                    nextHost();
                    return;
                }
            } catch (RemoteException e) {
                host = null;
                return;
            }
            long t0 = SystemClock.elapsedRealtime();
            List<String> changed = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int count = 0;
            for (MapItem item : allItems()) {
                String cot = toCot(item);
                if (cot == null)
                    continue;
                count++;
                watch(item);
                String uid = item.getUID();
                seen.add(uid);
                int print = fingerprint(cot);
                Integer before = sent.get(uid);
                if (before == null || before != print) {
                    sent.put(uid, print);
                    changed.add(cot);
                }
            }
            List<String> gone = new ArrayList<>();
            for (Iterator<String> it = sent.keySet().iterator(); it.hasNext();) {
                String uid = it.next();
                if (!seen.contains(uid)) {
                    it.remove();
                    gone.add(uid);
                    unwatch(uid);
                }
            }
            sentCount = sent.size();
            send(h, changed, gone);
            sendConfig();
            if (!changed.isEmpty() || !gone.isEmpty())
                Log.d(TAG, "sweep: items " + count + ", changed " + changed.size() + ", gone "
                    + gone.size() + ", " + (SystemClock.elapsedRealtime() - t0) + " ms, events "
                    + eventCounts);
            eventCounts.clear();
            worker.postDelayed(this, SWEEP_MS);
        }
    };

    // ---- Własna pozycja i stan połączenia ATAK ---------------------------------------------

    private final Runnable stateTask = new Runnable() {
        @Override
        public void run() {
            final ICompanionHost h = host;
            if (h == null)
                return;
            try {
                String json = state();
                if (!json.equals(lastState)) {
                    lastState = json;
                    h.state(json);
                }
            } catch (RemoteException e) {
                host = null;
                return;
            } catch (Exception e) {
                Log.w(TAG, "state failed: " + e.getClass().getSimpleName());
            }
            worker.postDelayed(this, STATE_MS);
        }
    };

    private String state() throws Exception {
        JSONObject root = new JSONObject();
        JSONObject self = new JSONObject();
        self.put("uid", MapView.getDeviceUid());
        // Identyfikator zostaje zawsze — aplikacja po nim pomija własny znacznik ATAK.
        final boolean sendSelf = prefs.getBoolean(PREF_SEND_SELF, true);
        if (sendSelf) {
            self.put("callsign", mapView.getDeviceCallsign());
            self.put("team", prefs.getString("locationTeam", "Cyan"));
            self.put("role", prefs.getString("atakRoleType", "Team Member"));
        }
        Marker marker = mapView.getSelfMarker();
        GeoPoint point = marker == null ? null : marker.getPoint();
        String motion;
        if (sendSelf && point != null && point.isValid() && !(point.getLatitude() == 0 && point.getLongitude() == 0)) {
            self.put("lat", point.getLatitude());
            self.put("lon", point.getLongitude());
            if (!Double.isNaN(point.getAltitude()))
                self.put("hae", point.getAltitude());
            // ATAK zapisuje w śladzie własnego znacznika kierunek, ale prędkość zawsze 0;
            // prawdziwa prędkość z odbiornika jest w polu „Speed”.
            double speed = marker.getMetaDouble("Speed", Double.NaN);
            if (Double.isNaN(speed))
                speed = marker.getTrackSpeed();
            double course = marker.getTrackHeading();
            if (!Double.isNaN(speed))
                self.put("speed", speed);
            if (!Double.isNaN(course))
                self.put("course", course);
            motion = (Double.isNaN(speed) ? "speed none" : speed > 0 ? "speed yes" : "speed zero")
                    + ", course " + (Double.isNaN(course) ? "none" : "yes");
        } else {
            motion = sendSelf ? "no position" : "not passed";
        }
        if (!motion.equals(lastMotion)) {
            lastMotion = motion;
            PluginLog.add("Own data from ATAK: " + motion);
        }
        // Tylko kompilacja testowa: zmyślona własna pozycja, która nie trafia do ATAK ani na serwer.
        double[] fake = testSelf;
        if (BuildConfig.DEBUG && fake != null && sendSelf) {
            self.put("lat", fake[0]);
            self.put("lon", fake[1]);
            self.put("hae", 120);
            // „nan” w danych testowych: pozycja bez prędkości albo bez kierunku.
            self.remove("speed");
            self.remove("course");
            if (!Double.isNaN(fake[2]))
                self.put("speed", fake[2]);
            if (!Double.isNaN(fake[3]))
                self.put("course", fake[3]);
            if (testCallsign != null)
                self.put("callsign", testCallsign);
        }
        root.put("self", self);
        JSONObject servers = new JSONObject();
        TAKServerListener listener = TAKServerListener.getInstance();
        servers.put("configured", listener.getServers() == null ? 0 : listener.getServers().length);
        servers.put("connected", listener.getConnectedServers() == null ? 0
                : listener.getConnectedServers().length);
        root.put("servers", servers);
        String map = prefs.getBoolean(PREF_SEND_ACTIVE_MAP, true) ? activeMap() : null;
        if (map != null)
            root.put("activeMap", map);
        return root.toString();
    }

    /** Nazwa mapy wybranej teraz w ATAK albo null, gdy nie da się jej ustalić. */
    private String activeMap() {
        try {
            for (Layer layer : mapView.getLayers(MapView.RenderStack.MAP_LAYERS)) {
                if (!(layer instanceof CardLayer))
                    continue;
                Layer active = ((CardLayer) layer).get();
                if (active instanceof RasterLayer2)
                    return ((RasterLayer2) active).getSelection();
            }
        } catch (Exception e) {
            Log.w(TAG, "active map failed: " + e.getClass().getSimpleName());
        }
        return null;
    }

    // ---- Konfiguracja: zestawy ikon i definicje map ----------------------------------------

    private String lastConfig = "";

    /** Wysyła wykaz zestawów ikon i definicje map online, jeśli zmienił się od ostatniego razu. */
    private void sendConfig() {
        final ICompanionHost h = host;
        if (h == null)
            return;
        try {
            JSONObject root = new JSONObject();
            JSONArray sets = new JSONArray();
            List<UserIconSet> iconSets = prefs.getBoolean(PREF_SEND_ICONS, true)
                    ? UserIconDatabase.instance(context).getIconSets(true, false)
                    : new ArrayList<UserIconSet>();
            for (UserIconSet set : iconSets) {
                if (set.getUid() == null)
                    continue;
                JSONObject o = new JSONObject();
                o.put("uid", set.getUid());
                o.put("name", set.getName());
                o.put("version", set.getVersion());
                o.put("count", set.getIcons() == null ? 0 : set.getIcons().size());
                sets.put(o);
            }
            root.put("iconsets", sets);
            JSONArray maps = new JSONArray();
            List<File> mapFiles = prefs.getBoolean(PREF_SEND_MAPS, true) ? mapSourceFiles() : new ArrayList<File>();
            for (File file : mapFiles) {
                JSONObject o = new JSONObject();
                o.put("file", file.getName());
                o.put("xml", new String(FileSystemUtils.read(file), StandardCharsets.UTF_8));
                maps.put(o);
            }
            root.put("maps", maps);
            String json = root.toString();
            if (json.equals(lastConfig))
                return;
            h.config(json);
            lastConfig = json;
            Log.i(TAG, "config: iconsets " + sets.length() + ", maps " + maps.length());
            PluginLog.add("Config sent: icon sets " + sets.length() + ", map definitions " + maps.length());
        } catch (RemoteException e) {
            host = null;
        } catch (Exception e) {
            Log.w(TAG, "config failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Pliki definicji map online znanych ATAK: z folderu źródeł map na każdym nośniku oraz
     * te, na które wskazuje baza warstw ATAK (mapy dodane wcześniej albo z paczki konfiguracyjnej).
     */
    private List<File> mapSourceFiles() {
        Map<String, File> found = new java.util.TreeMap<>();
        File[] dirs = FileSystemUtils.getItems("imagery/mobile/mapsources");
        if (dirs != null) {
            for (File dir : dirs) {
                File[] files = dir.listFiles();
                if (files == null)
                    continue;
                for (File file : files)
                    addMapSource(found, file);
            }
        }
        try {
            RasterDataStore.DatasetDescriptorCursor cursor = LayersMapComponent.getLayersDatabase().queryDatasets();
            try {
                while (cursor.moveToNext()) {
                    String uri = cursor.get().getUri();
                    if (uri == null)
                        continue;
                    if (uri.startsWith("file://"))
                        uri = uri.substring("file://".length());
                    addMapSource(found, new File(uri));
                }
            } finally {
                cursor.close();
            }
        } catch (Exception e) {
            Log.w(TAG, "map datasets failed: " + e.getClass().getSimpleName());
        }
        return new ArrayList<>(found.values());
    }

    private static void addMapSource(Map<String, File> found, File file) {
        if (file.isFile() && file.getName().toLowerCase(Locale.ROOT).endsWith(".xml")
                && file.length() <= MAX_MAP_XML)
            found.put(file.getAbsolutePath(), file);
    }

    /** Pisze zestaw ikon jako zip do potoku; czyta go AutoTAK. */
    private void writeIconSet(String uid, ParcelFileDescriptor out) {
        // Czytający zamyka potok po ostatnim pliku, zanim zip dopisze swój spis — to nie błąd.
        boolean complete = false;
        try (ZipOutputStream zip = new ZipOutputStream(
                new ParcelFileDescriptor.AutoCloseOutputStream(out))) {
            UserIconDatabase db = UserIconDatabase.instance(context);
            UserIconSet set = db.getIconSet(uid, true, false);
            if (set == null || set.getIcons() == null)
                return;
            StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<iconset name=\"")
                    .append(escape(set.getName())).append("\" uid=\"").append(escape(set.getUid()))
                    .append("\" version=\"").append(set.getVersion()).append("\">\n");
            for (UserIcon icon : set.getIcons())
                xml.append("  <icon name=\"").append(escape(icon.getFileName())).append("\"/>\n");
            xml.append("</iconset>\n");
            zip.putNextEntry(new ZipEntry("iconset.xml"));
            zip.write(xml.toString().getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            Set<String> written = new HashSet<>();
            for (UserIcon icon : set.getIcons()) {
                byte[] bytes = db.getIconBytes(icon.getId());
                if (bytes == null || icon.getFileName() == null)
                    continue;
                String group = icon.getGroup();
                String path = (group == null || group.isEmpty() ? "" : group + "/") + icon.getFileName();
                if (!written.add(path))
                    continue;
                zip.putNextEntry(new ZipEntry(path));
                zip.write(bytes);
                zip.closeEntry();
            }
            complete = true;
        } catch (Exception e) {
            if (!complete)
                Log.w(TAG, "icon set transfer failed: " + e.getClass().getSimpleName());
        }
    }

    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ---- Polecenia z AutoTAK ---------------------------------------------------------------

    private final ICompanionPlugin.Stub plugin = new ICompanionPlugin.Stub() {
        @Override
        public void requestSnapshot() {
            worker.post(CompanionLink.this::snapshot);
        }

        @Override
        public ParcelFileDescriptor openIconSet(final String uid) {
            try {
                final ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                new Thread(() -> writeIconSet(uid, pipe[1]), "AutoTAK-icons").start();
                return pipe[0];
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public void select(final String uid) {
            // Reakcja według ustawienia wtyczki: nic, przesunięcie mapy albo przesunięcie i menu obiektu.
            echoUid = uid;
            echoUntil = SystemClock.elapsedRealtime() + ECHO_MS;
            worker.post(() -> reportedUid = uid);
            final String action = prefs.getString(PREF_SELECT, "pan");
            Log.d(TAG, "select: action " + action + (uid == null ? ", cleared" : ""));
            PluginLog.add("Selection from AutoTAK: action " + action + (uid == null ? " (cleared)" : ""));
            if ("none".equals(action))
                return;
            final boolean menu = "menu".equals(action);
            mapView.post(() -> {
                if (uid == null) {
                    if (menu) {
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent("com.atakmap.android.maps.HIDE_MENU"));
                        AtakBroadcast.getInstance().sendBroadcast(
                                new Intent("com.atakmap.android.maps.HIDE_DETAILS"));
                    }
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent("com.atakmap.android.maps.UNFOCUS"));
                    return;
                }
                if (mapView.getRootGroup().deepFindUID(uid) == null)
                    return;
                AtakBroadcast.getInstance().sendBroadcast(
                        new Intent("com.atakmap.android.maps.FOCUS").putExtra("uid", uid));
                // Jak dotknięcie obiektu w ATAK: menu kołowe i okno informacji w prawym górnym rogu.
                if (menu) {
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent("com.atakmap.android.maps.SHOW_MENU").putExtra("uid", uid));
                    AtakBroadcast.getInstance().sendBroadcast(
                            new Intent("com.atakmap.android.maps.SHOW_DETAILS").putExtra("uid", uid));
                }
            });
        }
    };

    // ---- Tylko kompilacja testowa: wstrzyknięcie obiektu do ATAK bez serwera ----------------

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // flaga istnieje od Androida 13
    private void registerTestReceiver() {
        testReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                String fake = intent.getStringExtra("self");
                if (fake != null) {
                    try {
                        String[] parts = fake.split(",");
                        testSelf = new double[] {
                                Double.parseDouble(parts[0]), Double.parseDouble(parts[1]),
                                Double.parseDouble(parts[2]), Double.parseDouble(parts[3])
                        };
                        testCallsign = parts.length > 4 ? parts[4] : null;
                    } catch (Exception e) {
                        testSelf = null;
                    }
                    return;
                }
                String cot = intent.getStringExtra("cot");
                if (cot == null)
                    return;
                CotEvent event = CotEvent.parse(cot);
                if (event != null && event.isValid())
                    CotMapComponent.getInternalDispatcher().dispatch(event);
                else
                    Log.w(TAG, "test inject: invalid CoT");
            }
        };
        IntentFilter filter = new IntentFilter(TEST_INJECT);
        if (Build.VERSION.SDK_INT >= 33)
            context.registerReceiver(testReceiver, filter, Context.RECEIVER_EXPORTED);
        else
            context.registerReceiver(testReceiver, filter);
    }
}
