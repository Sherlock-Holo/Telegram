package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.google.android.gms.tasks.CancellationToken;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.TaskCompletionSource;

import org.telegram.tgnet.TLRPC;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

/**
 * No-FCM stub implementation of FirebasePhoneNumberVerificationHelper.
 * All Firebase Phone Number Verification functionality is disabled in the fork build.
 * Methods return empty results or fail gracefully when BuildVars.SUPPORTS_FPNV is false.
 */
public class FirebasePhoneNumberVerificationHelper {

    // Stub class to replace com.google.firebase.pnv.VerificationSupportResult
    public static class VerificationSupportResult {
        public boolean isSupported() { return false; }
        public int getSimSlot() { return -1; }
        public String getCarrierId() { return null; }
    }

    private FirebasePhoneNumberVerificationHelper() {
    }

    public static class SimInfo {
        public int slot;
        public String carrierName;
        public String phoneNumber;
    }

    public static SimInfo getSimInfo(Context context, VerificationSupportResult result) {
        final SimInfo info = new SimInfo();
        info.slot = result.getSimSlot();
        info.carrierName = emptyToNull(result.getCarrierId());
        try {
            TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
            if (tm == null) {
                return info;
            }
            final int subId = getSubscriptionId(context, info.slot);
            if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                tm = tm.createForSubscriptionId(subId);
            } else if (tm.getPhoneCount() > 1) {
                return info;
            }

            String carrierName = tm.getSimOperatorName();
            if (TextUtils.isEmpty(carrierName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                final CharSequence idName = tm.getSimCarrierIdName();
                carrierName = idName == null ? null : idName.toString();
            }
            if (!TextUtils.isEmpty(carrierName)) {
                info.carrierName = carrierName;
            }

            info.phoneNumber = emptyToNull(readPhoneNumber(context, tm, subId));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return info;
    }

    private static int getSubscriptionId(Context context, int slot) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                return SubscriptionManager.getSubscriptionId(slot);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return getSubscriptionIdQ(context, slot);
            } else if (hasPermission(context, Manifest.permission.READ_PHONE_STATE)) {
                final SubscriptionManager sm = (SubscriptionManager) context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE);
                final SubscriptionInfo subscriptionInfo = sm == null ? null : sm.getActiveSubscriptionInfoForSimSlotIndex(slot);
                if (subscriptionInfo != null) {
                    return subscriptionInfo.getSubscriptionId();
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static int getSubscriptionIdQ(Context context, int slot) {
        final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
        final int[] ids = sm == null ? null : sm.getSubscriptionIds(slot);
        return ids != null && ids.length > 0 ? ids[0] : SubscriptionManager.INVALID_SUBSCRIPTION_ID;
    }

    private static String readPhoneNumber(Context context, TelephonyManager tm, int subId) {
        final boolean allowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? hasPermission(context, Manifest.permission.READ_PHONE_NUMBERS)
            : hasPermission(context, Manifest.permission.READ_PHONE_STATE);
        if (!allowed) {
            return null;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                final SubscriptionManager sm = context.getSystemService(SubscriptionManager.class);
                final String number = sm == null ? null : sm.getPhoneNumber(subId);
                if (!TextUtils.isEmpty(number)) {
                    return number;
                }
            }
            return tm.getLine1Number();
        } catch (SecurityException e) {
            return null;
        }
    }

    private static boolean hasPermission(Context context, String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private static String emptyToNull(String value) {
        return TextUtils.isEmpty(value) ? null : value;
    }

    /**
     * No-FCM stub: always returns empty list when SUPPORTS_FPNV is false.
     */
    public static void getVerificationSupportInfo(Utilities.Callback<List<VerificationSupportResult>> callback) {
        callback.run(Collections.emptyList());
    }

    /**
     * No-FCM stub: always fails with "FPNV not supported" exception.
     */
    public static Task<TLRPC.auth_Authorization> loginViaFirebasePhoneNumberVerification(
        Context context,
        int currentAccount,
        CancellationToken cancellationToken,
        @Nullable Runnable onVerifying
    ) {
        final TaskCompletionSource<TLRPC.auth_Authorization> taskSource = new TaskCompletionSource<>(cancellationToken);
        taskSource.trySetException(new Exception("Firebase Phone Number Verification not supported in no-FCM build"));
        return taskSource.getTask();
    }
}
