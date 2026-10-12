package io.element.android.x.acceptance;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.io.File;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;

/** Test-APK-only probe: no key access, no plaintext message export, and only fixed SELECT statements. */
public final class ReadOnlyDatabaseProbe extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!getTargetContext().getPackageName().equals("com.yizhe.chat.next.debug") ||
                (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                throw new IllegalStateException("Probe requires the Next debug QA package");
            }
            startActivitySync(new Intent().setClassName(getTargetContext(), "io.element.android.x.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            Class<?> registry = Class.forName("io.element.encrypteddb.OpenDatabaseSecurityRegistry",
                true, getTargetContext().getClassLoader());
            Field entriesField = registry.getDeclaredField("openDatabases");
            entriesField.setAccessible(true);
            Map<?, ?> entries = null;
            for (int attempt = 0; attempt < 75; attempt++) {
                entries = (Map<?, ?>) entriesField.get(null);
                if (entries.containsKey("session_database.db")) break;
                Thread.sleep(200);
            }
            JSONArray databases = new JSONArray();
            for (Map.Entry<?, ?> entry : entries.entrySet()) {
                Object database = ((WeakReference<?>) entry.getValue()).get();
                if (database == null) continue;
                String name = String.valueOf(entry.getKey());
                JSONObject projection = new JSONObject().put("database", name);
                if (name.equals("session_database.db")) {
                    JSONArray sessions = select(database,
                        "SELECT userId,deviceId,homeserverUrl,isTokenValid,lastUsageIndex,sessionPath,cachePath FROM SessionData ORDER BY lastUsageIndex DESC");
                    projection.put("sessions", sessions);
                    JSONArray sdkStores = new JSONArray();
                    for (int index = 0; index < sessions.length(); index++) {
                        JSONObject session = sessions.getJSONObject(index);
                        for (String pathField : new String[]{"sessionPath", "cachePath"}) {
                            String path = session.optString(pathField);
                            if (!path.isEmpty()) inspectStores(new File(path), 0, sdkStores);
                        }
                    }
                    projection.put("sdk_store_metadata", sdkStores);
                    projection.put("schema", select(database, "PRAGMA user_version"));
                } else if (name.equals("cache_database.db")) {
                    projection.put("row_counts", select(database, "SELECT count(*) AS row_count FROM CacheData"));
                } else if (name.equals("push_database.db")) {
                    projection.put("tables", select(database, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"));
                }
                databases.put(projection);
            }
            result.putString("projection", new JSONObject().put("mode", "READ_ONLY_FIXED_QUERIES")
                .put("databases", databases).put("offline_sdk_event_projection", inspectQaEvents()).toString());
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            result.putString("probe_error", error.getClass().getName() + ":" + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    /** The controller disables Wi-Fi and mobile data before this cold process starts. */
    private JSONObject inspectQaEvents() throws Exception {
        ConnectivityManager connectivity = (ConnectivityManager) getTargetContext().getSystemService("connectivity");
        NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
        boolean online = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        ClassLoader loader = getTargetContext().getClassLoader();
        Object application = getTargetContext().getApplicationContext();
        Object graph = application.getClass().getMethod("getGraph").invoke(application);
        Field providerField = graph.getClass().getDeclaredField("matrixSessionCacheProvider");
        providerField.setAccessible(true);
        Object provider = providerField.get(graph);
        Method get = provider.getClass().getMethod("invoke");
        get.setAccessible(true);
        Object cache = get.invoke(provider);
        Field sessionsField = cache.getClass().getDeclaredField("sessionIdsToMatrixSession");
        sessionsField.setAccessible(true);
        Object matrixClient = null;
        String userId = null;
        for (int attempt = 0; attempt < 75 && matrixClient == null; attempt++) {
            Map<?, ?> sessions = (Map<?, ?>) sessionsField.get(cache);
            for (Map.Entry<?, ?> session : sessions.entrySet()) {
                String candidate = (String) session.getKey().getClass().getMethod("getValue").invoke(session.getKey());
                if (!candidate.equals("@yizhea13100559290:matrix-next.yizhesy.cn") &&
                    !candidate.equals("@yizhe1auth1s0059262e9:matrix-next.yizhesy.cn")) continue;
                Method clientGetter = session.getValue().getClass().getMethod("getMatrixClient");
                clientGetter.setAccessible(true);
                matrixClient = clientGetter.invoke(session.getValue());
                userId = candidate;
            }
            if (matrixClient == null) Thread.sleep(200);
        }
        if (matrixClient == null) return new JSONObject().put("status", "NOT_RUN_QA_SESSION_NOT_RESTORED");
        Field innerField = matrixClient.getClass().getDeclaredField("innerClient");
        innerField.setAccessible(true);
        Object client = innerField.get(matrixClient);
        String roomId = "!AfvmtNOCheClDpeoSu:matrix-next.yizhesy.cn";
        Object room = client.getClass().getMethod("getRoom", String.class).invoke(client, roomId);
        if (room == null) return new JSONObject().put("status", "NOT_RUN_QA_ROOM_NOT_RESTORED");
        if (online) {
            // No load-or-fetch calls online: only the SDK's local latest-event metadata.
            Object latest = suspendRead(room, "latestEvent", null, loader);
            JSONObject metadata = new JSONObject().put("status", "READ_ONLY_SDK_LATEST_METADATA")
                .put("user_id", userId).put("room_id", roomId).put("latest_value_class", latest.getClass().getSimpleName());
            try {
                metadata.put("sender_id", latest.getClass().getMethod("getSender").invoke(latest));
                metadata.put("timestamp_ms", latest.getClass().getMethod("getTimestamp-s-VKNKU").invoke(latest));
            } catch (NoSuchMethodException ignored) { metadata.put("metadata_available", false); }
            latest.getClass().getMethod("destroy").invoke(latest);
            room.getClass().getMethod("close").invoke(room);
            return metadata;
        }
        String[] events = {
            "$yGCu4iBAbQklIN7_PS54IFKIlxZryfPOYUx0D_cBXBs",
            "$7XApQZiYvqDZGcWHhOD3YsfzwArRuShS8hq6kdU-Lbs",
            "$gkJlWbhtfkA3UQNI75K9px2s-dLl5rdJJqZqhyFLe-w",
            "$0_ar2RqiodANaEg5ni7fuGRPBe52qKWFapgmb1UiwDI",
            "$NHWi4P0_FhW6-0EyrCoYTh7fhyIpl6JV1hXCTsX8ydw",
            "$0Kyw4Bo0k5HnubntYEcIN1RViXXfqVpXFGxgYN--eUw",
            "$WMCZHA3TJD4bnEgFP1xiLdyh9e7LxOyDlWykgBy_4AA",
            "$qSVjuWlceykNF5sdRf_jvviUEpAgwIaGKoV4BNKbKFY",
            "$yjmXmH4YwEpHvk8b8IgQxQ1tE0wEliuI_giHfvDHjYI",
            "$7cObV-QQwiBOtoNLoh4kP_stfPN_LKBSlB6Mm6WT4vg",
            "$hGTrGbQDi7Adpakfp5KQPGlckv-NhAuBqlWqOO8oG7s",
            "$PL2CNV6idTM1LZxliokPM4hnZSFVXcB3zXkjCnfiGg8",
            "$jdiklbpS7TJb1glVZWfNqpVqaPDC33l45n1N__YWuJk",
            "$_uCYn62qyLO2Sf3_m1lrug2tezwERwhO3c95fVsztvg",
            "$314gHfh-ZCn67IqU8n4IutVwOLx3HmotW28jZrKI1Wk",
            "$3NWhgWdkBRM7HndzLY6RAfWv54k_LodHCC0XGSNk1QU",
            "$telu8furss4FqWUGY1G6--japjD3uBlyqGtS6tRwZr0",
            "$oyUXK3xCKiBkro6C_3RPUsgrimSPql6NGSi56Mkc3ho",
            "$9vly5E_ZMg9ni0tU9pEwzkV1RROx3PuDZTCtqQbVqok"
        };
        JSONArray rows = new JSONArray();
        long deadline = System.currentTimeMillis() + 25000;
        for (String eventId : events) {
            if (System.currentTimeMillis() >= deadline) break;
            JSONObject row = new JSONObject().put("requested_event_id", eventId);
            try {
                Object event = suspendRead(room, "loadOrFetchEvent", eventId, loader);
                row.put("event_id", event.getClass().getMethod("eventId").invoke(event));
                row.put("sender_id", event.getClass().getMethod("senderId").invoke(event));
                row.put("status", "FOUND_WITHOUT_NETWORK");
                event.getClass().getMethod("close").invoke(event);
            } catch (Exception error) {
                row.put("status", "NOT_OBSERVED").put("error_class", error.getClass().getSimpleName());
            }
            rows.put(row);
        }
        room.getClass().getMethod("close").invoke(room);
        return new JSONObject().put("status", "READ_ONLY_OFFLINE_COLD_PROCESS")
            .put("user_id", userId).put("room_id", roomId).put("internet_network_available", false)
            .put("requested_count", events.length).put("events", rows);
    }
    private Object suspendRead(Object target, String method, String eventId, ClassLoader loader) throws Exception {
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", true, loader);
        Object context = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, loader).getField("INSTANCE").get(null);
        Object[] outcome = new Object[1];
        CountDownLatch finished = new CountDownLatch(1);
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{continuation}, (proxy, invoked, args) -> {
            if (invoked.getName().equals("getContext")) return context;
            if (invoked.getName().equals("resumeWith")) { outcome[0] = args[0]; finished.countDown(); }
            return null;
        });
        Object direct = eventId == null
            ? target.getClass().getMethod(method, continuation).invoke(target, callback)
            : target.getClass().getMethod(method, String.class, continuation).invoke(target, eventId, callback);
        Object suspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", true, loader)
            .getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        if (direct != suspended) outcome[0] = direct;
        else if (!finished.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("QA_CACHE_READ_TIMEOUT");
        if (outcome[0] == null || outcome[0].getClass().getName().equals("kotlin.Result$Failure")) {
            throw new IllegalStateException("QA_CACHE_EVENT_NOT_OBSERVED");
        }
        return outcome[0];
    }
    private static JSONArray select(Object database, String sql) throws Exception {
        JSONArray rows = new JSONArray();
        Cursor queried;
        if (database instanceof SQLiteDatabase) {
            queried = ((SQLiteDatabase) database).rawQuery(sql, null);
        } else {
            Method query = database.getClass().getMethod("query", String.class);
            query.setAccessible(true);
            queried = (Cursor) query.invoke(database, sql);
        }
        try (Cursor cursor = queried) {
            while (cursor.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int index = 0; index < cursor.getColumnCount(); index++) {
                    row.put(cursor.getColumnName(index), cursor.isNull(index) ? JSONObject.NULL : cursor.getString(index));
                }
                rows.put(row);
            }
        }
        return rows;
    }
    private static void inspectStores(File folder, int depth, JSONArray stores) throws Exception {
        if (depth > 3 || stores.length() >= 20) return;
        File[] files = folder.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) { inspectStores(file, depth + 1, stores); continue; }
            if (!(file.getName().endsWith(".db") || file.getName().endsWith(".sqlite3")) || file.getName().contains("crypto")) continue;
            JSONObject metadata = new JSONObject().put("file", file.getPath()).put("size", file.length());
            try (SQLiteDatabase database = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                metadata.put("schema_tables", select(database, "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"));
                if (file.getName().equals("matrix-sdk-event-cache.sqlite3")) {
                    metadata.put("events_schema", select(database, "PRAGMA table_info(events)"));
                    metadata.put("event_chunks_schema", select(database, "PRAGMA table_info(event_chunks)"));
                }
                if (file.getName().equals("matrix-sdk-state.sqlite3")) {
                    metadata.put("send_queue_schema", select(database, "PRAGMA table_info(send_queue_events)"));
                }
            } catch (Exception error) { metadata.put("read_only_open_error", error.getClass().getSimpleName()); }
            stores.put(metadata);
        }
    }
}
