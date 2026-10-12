package io.element.android.x.acceptance;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Fixed QA receipt reads through the application's existing signed HTTP path.
 * No authenticator fields, credentials, private keys or user-content payloads are exported.
 */
public final class ReadOnlyReceiptProbe extends Instrumentation {
    private static final String INSTALLATION = "d16d640ee92e7fc08cd02ce04b7bfaf642d02116599636516727c5c30704a2e1";
    private static final String PUBLIC_DER = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEHbgnDsO7LCQnQvV0xuUYzis4m76eTaDMKMcrfphJBTM5SfmDWCJudYQezZF0kiW5tLPrgl8+KEd19Mh546yzWw==";
    private static final String[][] RECEIPTS = {
        {"pdYs7HBNsLuVKsBVVhpeldIPygVsaXcu", "62fa1e2e-3c4e-46f3-afc3-2747014291ed", "SUCCEEDED"},
        {"dYei_QBAa2FL5lmZaMDpE-IDfaZVtpf0", "media-caption-fb897b9981905c6cde3080b6c5e512420cc820448cea6a42224022cc24133d37", "SUCCEEDED"},
        {"hl7D4KkS-FHWh2X1nGVe5BF_pOXUcgTj", "lc13-receive-36ec52d642801f01b9ec2544bbd9ec27bb3b3a39dc686c4f6dd9c4cfc0e3b6b4", "SUCCEEDED"},
        {"9dHjtunBNKsJnsP1sVfCcDPWhXt7aJ3n", "46ee82c5-8482-4c7e-87fa-a8db7c531759", "SUCCEEDED"},
        {"s685KeTp_fGpZ33w8WqK-8-elG5zQdIE", "28d9b055-6c37-48c5-b023-e9ba67ff27e5", "SUCCEEDED"},

        {"LcplnBQp_O9JR7UbDXBv5S9QBhPZizAY", "a3ee2bb3-de3f-4d12-90bf-db77849eca28", "FAILED_FINAL"},
        {"TVHJPBFuiRq2BQ8-1AqPvHsGxut2A4Do", "99453267-f5c1-464f-aa11-cc857131b30e", "CANCELLED"},
        {"Xcjo_F4ddcYG8Jq8JPPbhzyfY8GuMBr6", "3a2603dc-f959-4a5a-8726-5e6381e5d1d9", "SUCCEEDED"},
        {"OV2Wl9b3r0dmeDKurxz77tbaiJN-AESN", "c09c23cb-7b1b-429b-ad6e-df4fadab82d8", "CANCELLED"},
        {"feC3nhhp8NoLKUrSUUfaSorUbwE9tSbf", "4e3f6501-2598-413b-883d-7c3fcaa61c7a", "CANCELLED"},
        {"Z15QFmVYMDUvNLRZ9F3nYXNk26WRU37O", "78811ffc-68e2-4455-8647-57ef458da5d3", "CANCELLED"},
        {"-jzxDbjOH1tT4pWG_hmzj7N4H8gIvWKC", "7ad9cd06-c967-4b7d-8bbf-bcfc95427bd6", "CANCELLED"},
        {"ooORq3UrLjbozuy1JWgMfTa3jX7pGQT3", "5c16a234-2682-4a95-bcf6-15e5b64adf87", "CANCELLED"},
        {"eMnuVoF6Pv_-Bx3Q4JGzCKXCou2paHv9", "4850db34-1a19-4270-a00f-dc0d80a7ab94", "CANCELLED"},
        {"zlQm8uUl4WSlMLf8HsVnn94D1BsCC6A5", "0ae068b2-1635-47d8-a68f-451022419b38", "CANCELLED"},
        {"Kab8pG6k7J6UHjrgmGw78Rnqf2UJ2gol", "2f6a7737-15c8-4502-91b9-8dd19b443104", "CANCELLED"},
        {"TPIu_bLi2NdAPvDWJ9Y3JuF8zRP83XZs", "c19b9e32-4796-4fc7-82f1-0f63491f3d56", "CANCELLED"},
    };
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            Intent launch = getTargetContext().getPackageManager().getLaunchIntentForPackage(getTargetContext().getPackageName());
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getTargetContext().startActivity(launch); Thread.sleep(3500);
            Object app = getTargetContext().getApplicationContext();
            Object graph = app.getClass().getMethod("getGraph").invoke(app);
            Method provider = graph.getClass().getMethod("managementControlService"); provider.setAccessible(true);
            Object service = provider.invoke(graph);
            ClassLoader loader = getTargetContext().getClassLoader();
            Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", true, loader);
            Method read = service.getClass().getDeclaredMethod("executeSignedGet", String.class, String.class, continuation);
            read.setAccessible(true);
            JSONArray reports = new JSONArray();
            for (String[] expected : RECEIPTS) {
                byte[] response = signedRead(service, read, loader, "/v1/lifecycle/receipts/" + expected[0]);
                try {
                    JSONObject wire = new JSONObject(new String(response, StandardCharsets.UTF_8));
                    String signature = wire.getString("signature_b64"); wire.remove("signature_b64");
                    if (!expected[0].equals(wire.getString("receipt_id")) || !expected[1].equals(wire.getString("operation_id")) ||
                        !expected[2].equals(wire.getString("terminal_state")) ||
                        !INSTALLATION.equals(wire.getJSONObject("subjects").getString("installation_id"))) throw new IllegalStateException("QA_RECEIPT_SCOPE_MISMATCH");
                    byte[] payload = canonical(wire).getBytes(StandardCharsets.UTF_8);
                    Signature verifier = Signature.getInstance("SHA256withECDSA");
                    verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_DER))));
                    verifier.update(payload); boolean valid = verifier.verify(Base64.getDecoder().decode(signature));
                    if (!valid) throw new IllegalStateException("QA_RECEIPT_SIGNATURE_INVALID");
                    JSONObject report = new JSONObject().put("receipt_id", expected[0]).put("operation_id", expected[1])
                        .put("terminal_state", expected[2]).put("signature_valid", true).put("subject_installation_matches", true)
                        .put("signed_payload_sha256", hex(MessageDigest.getInstance("SHA-256").digest(payload)))
                        .put("failure_preserved", wire.has("failure")).put("unsigned_optional_null_fields", countNulls(wire));
                    reports.put(report); java.util.Arrays.fill(payload, (byte) 0);
                } finally { java.util.Arrays.fill(response, (byte) 0); }
            }
            boolean foreignDenied = false;
            try {
                byte[] response = signedRead(service, read, loader, "/v1/lifecycle/receipts/cnLpqKouHgLDcKS-Cwre6XCiIZ9DpyAd");
                java.util.Arrays.fill(response, (byte) 0);
            } catch (Throwable error) {
                Throwable cause = unwrap(error);
                if (cause.getClass().getName().endsWith("HttpStatusException")) {
                    Method status = cause.getClass().getDeclaredMethod("getStatusCode"); status.setAccessible(true);
                    foreignDenied = ((Integer) status.invoke(cause)) == 403;
                }
            }
            if (!foreignDenied) throw new IllegalStateException("QA_FOREIGN_INSTALLATION_NOT_DENIED");
            result.putString("receipt_probe", new JSONObject().put("mode", "READ_ONLY_FIXED_QA_SIGNED_HTTP")
                .put("reports", reports).put("foreign_installation_http_403", true).put("credentials_or_private_keys_exported", false).toString());
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("probe_error_class", unwrap(error).getClass().getName());
            finish(Activity.RESULT_CANCELED, result);
        }
    }
    private byte[] signedRead(Object target, Method method, ClassLoader loader, String path) throws Throwable {
        Class<?> continuation = Class.forName("kotlin.coroutines.Continuation", true, loader);
        Object empty = Class.forName("kotlin.coroutines.EmptyCoroutineContext", true, loader).getField("INSTANCE").get(null);
        CountDownLatch done = new CountDownLatch(1); Object[] value = new Object[1];
        Object callback = Proxy.newProxyInstance(loader, new Class<?>[]{continuation}, (proxy, called, args) -> {
            if (called.getName().equals("getContext")) return empty;
            if (called.getName().equals("resumeWith")) { value[0] = args[0]; done.countDown(); }
            return null;
        });
        Object direct = method.invoke(target, path, null, callback);
        Object suspended = Class.forName("kotlin.coroutines.intrinsics.IntrinsicsKt", true, loader).getMethod("getCOROUTINE_SUSPENDED").invoke(null);
        if (direct != suspended) value[0] = direct;
        else if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("QA_RECEIPT_HTTP_TIMEOUT");
        if (value[0] != null && value[0].getClass().getName().equals("kotlin.Result$Failure")) {
            throw (Throwable) value[0].getClass().getField("exception").get(value[0]);
        }
        if (!(value[0] instanceof byte[])) throw new IllegalStateException("QA_RECEIPT_HTTP_NO_RESULT");
        return (byte[]) value[0];
    }
    private static Throwable unwrap(Throwable error) {
        while (error instanceof InvocationTargetException) error = ((InvocationTargetException) error).getTargetException();
        return error;
    }
    private static String canonical(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject obj = (JSONObject) value; List<String> keys = new ArrayList<>();
            Iterator<String> iterator = obj.keys(); while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            StringBuilder result = new StringBuilder("{"); boolean first = true;
            for (String key : keys) { if (!first) result.append(','); first = false; result.append(JSONObject.quote(key)).append(':').append(canonical(obj.get(key))); }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray list = (JSONArray) value; StringBuilder result = new StringBuilder("[");
            for (int i=0;i<list.length();i++) { if(i>0) result.append(','); result.append(canonical(list.get(i))); }
            return result.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String)value);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        throw new IllegalStateException("QA_RECEIPT_NON_JSON_VALUE");
    }
    private static int countNulls(JSONObject value) throws Exception {
        int count = 0; Iterator<String> keys = value.keys(); while(keys.hasNext()) if(value.isNull(keys.next())) count++; return count;
    }
    private static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for(byte value:bytes) result.append(String.format("%02x", value & 255)); return result.toString(); }
}
