package com.kankan.globaltraveling;

import android.bluetooth.BluetoothAdapter;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.SystemClock;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * GlobalTraveling - Modern libxposed API 102 implementation.
 *
 * Keeps the original hook targets and behavior while using the API 102
 * interceptor-chain model and ordinary Java reflection for target classes.
 */
public class HookMain extends XposedModule {

    private static final String TAG = "GlobalTraveling";
    private static final String MODULE_PACKAGE = "com.kankan.globaltraveling";
    private static final String FILE_PATH = "/data/local/tmp/irest_loc.conf";

    private static String cachedMac = "00:11:22:33:44:55";
    private static String cachedSSID = "Mandba-WiFi";
    private final Set<Method> hookedListenerMethods =
            Collections.newSetFromMap(new ConcurrentHashMap<Method, Boolean>());

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        log(android.util.Log.INFO, TAG,
                "Modern libxposed API 102 loaded: " + param.getProcessName());
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        String packageName = param.getPackageName();
        if (MODULE_PACKAGE.equals(packageName)) return;

        try {
            installHooks(param.getClassLoader());
            log(android.util.Log.INFO, TAG, "Hooks installed for " + packageName);
        } catch (Throwable t) {
            log(android.util.Log.ERROR, TAG,
                    "Hook installation failed for " + packageName, t);
        }
    }

    private void installHooks(ClassLoader classLoader) throws Throwable {
        // ==========================================
        // 1. Location core hooks
        // ==========================================
        XposedInterface.Hooker locationMutationHook = chain -> {
            Object result = chain.proceed();
            Object receiver = chain.getThisObject();
            if (receiver instanceof Location) {
                applyLocationFix((Location) receiver);
            }
            return result;
        };

        hook(Location.class.getDeclaredConstructor(String.class), locationMutationHook);
        hook(Location.class.getDeclaredMethod("set", Location.class), locationMutationHook);

        XposedInterface.Hooker getterHook = chain -> {
            Object result = chain.proceed();
            double[] c = readFromTmp();
            if (c == null) return result;

            double d = getDrift();
            String name = chain.getExecutable().getName();
            if ("getLatitude".equals(name)) return c[0] + d;
            if ("getLongitude".equals(name)) return c[1] + d;
            if ("getAccuracy".equals(name)) return 3.0f + new Random().nextFloat();
            if ("getSpeed".equals(name)) return 0.0f;
            if ("getAltitude".equals(name)) return 50.0d;
            return result;
        };

        hook(Location.class.getDeclaredMethod("getLatitude"), getterHook);
        hook(Location.class.getDeclaredMethod("getLongitude"), getterHook);
        hook(Location.class.getDeclaredMethod("getAccuracy"), getterHook);
        hook(Location.class.getDeclaredMethod("getSpeed"), getterHook);
        hook(Location.class.getDeclaredMethod("getAltitude"), getterHook);

        hook(Location.class.getDeclaredMethod("isFromMockProvider"), chain -> false);

        // ==========================================
        // 2. LastKnownLocation
        // ==========================================
        hookAllMethods(LocationManager.class, "getLastKnownLocation", chain -> {
            Location loc = (Location) chain.proceed();
            if (loc == null && readFromTmp() != null) {
                loc = new Location(LocationManager.GPS_PROVIDER);
            }
            if (loc != null) applyLocationFix(loc);
            return loc;
        });

        // ==========================================
        // 3. Dynamic LocationListener interception
        // ==========================================
        hookAllMethods(LocationManager.class, "requestLocationUpdates", chain -> {
            List<Object> args = chain.getArgs();
            for (Object arg : args) {
                if (!(arg instanceof LocationListener)) {
                    continue;
                }

                Method onLocationChanged = findMethod(
                        arg.getClass(), "onLocationChanged", Location.class);
                if (onLocationChanged != null && hookedListenerMethods.add(onLocationChanged)) {
                    hook(onLocationChanged, listenerChain -> {
                        Object locationArg = listenerChain.getArg(0);
                        if (locationArg instanceof Location) {
                            applyLocationFix((Location) locationArg);
                        }
                        return listenerChain.proceed();
                    });
                }

                double[] c = readFromTmp();
                if (c != null) {
                    Location fastLoc = new Location(LocationManager.GPS_PROVIDER);
                    applyLocationFix(fastLoc);
                    try {
                        ((LocationListener) arg).onLocationChanged(fastLoc);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return chain.proceed();
        });

        // ==========================================
        // 4. Wi-Fi / network spoofing
        // ==========================================
        hook(TelephonyManager.class.getDeclaredMethod("getNetworkType"), chain ->
                TelephonyManager.NETWORK_TYPE_UNKNOWN);

        hookIfPresent(ConnectivityManager.class, "getActiveNetworkInfo", new Class<?>[0], chain -> {
            Object result = chain.proceed();
            if (readFromTmp() != null && result instanceof NetworkInfo) {
                NetworkInfo info = (NetworkInfo) result;
                setField(info, "mNetworkType", ConnectivityManager.TYPE_WIFI);
                setField(info, "mTypeName", "WIFI");
                setField(info, "mState", NetworkInfo.State.CONNECTED);
            }
            return result;
        });

        hook(WifiManager.class.getDeclaredMethod("getConnectionInfo"), chain -> {
            Object result = chain.proceed();
            if (readFromTmp() != null && result instanceof WifiInfo) {
                WifiInfo info = (WifiInfo) result;
                setField(info, "mBSSID", cachedMac);
                setField(info, "mSSID", "\"" + cachedSSID + "\"");
                setField(info, "mMacAddress", "02:00:00:00:00:00");
                setField(info, "mRssi", -45 - new Random().nextInt(10));
            }
            return result;
        });

        hook(WifiManager.class.getDeclaredMethod("getScanResults"), chain -> {
            if (readFromTmp() == null) return chain.proceed();

            List<ScanResult> list = new ArrayList<>();
            try {
                Constructor<ScanResult> ctor = ScanResult.class.getDeclaredConstructor();
                ctor.setAccessible(true);
                ScanResult w = ctor.newInstance();
                w.SSID = cachedSSID;
                w.BSSID = cachedMac;
                w.level = -45 - new Random().nextInt(10);
                w.capabilities = "[WPA2-PSK-CCMP][ESS]";
                w.frequency = 2412;
                if (Build.VERSION.SDK_INT >= 17) {
                    w.timestamp = SystemClock.elapsedRealtime() * 1000;
                }
                list.add(w);
            } catch (Throwable ignored) {
            }
            return list;
        });

        // ==========================================
        // 5. SIM / cell / Bluetooth suppression
        // ==========================================
        hook(TelephonyManager.class.getDeclaredMethod("getSimState"), chain ->
                TelephonyManager.SIM_STATE_ABSENT);
        hookIfPresent(TelephonyManager.class, "getSimState", new Class<?>[]{int.class},
                chain -> TelephonyManager.SIM_STATE_ABSENT);
        hook(TelephonyManager.class.getDeclaredMethod("getAllCellInfo"), chain ->
                new ArrayList<CellInfo>());
        hook(TelephonyManager.class.getDeclaredMethod("getCellLocation"), chain -> null);
        hook(TelephonyManager.class.getDeclaredMethod("getNetworkOperator"), chain -> "");

        if (Build.VERSION.SDK_INT >= 29) {
            try {
                hook(TelephonyManager.class.getDeclaredMethod(
                                "requestCellInfoUpdate", Executor.class, TelephonyManager.CellInfoCallback.class),
                        chain -> {
                            if (readFromTmp() == null) return chain.proceed();
                            Executor ex = (Executor) chain.getArg(0);
                            TelephonyManager.CellInfoCallback cb =
                                    (TelephonyManager.CellInfoCallback) chain.getArg(1);
                            if (ex != null && cb != null) {
                                ex.execute(() -> cb.onCellInfo(new ArrayList<>()));
                            }
                            return null;
                        });
            } catch (Throwable ignored) {
            }
        }

        hook(TelephonyManager.class.getDeclaredMethod(
                        "listen", PhoneStateListener.class, int.class), chain -> {
            if (readFromTmp() != null) {
                Object[] args = chain.getArgs().toArray();
                if (args.length > 1) {
                    args[1] = PhoneStateListener.LISTEN_NONE;
                    return chain.proceed(args);
                }
            }
            return chain.proceed();
        });

        try {
            hook(BluetoothAdapter.class.getDeclaredMethod("getBondedDevices"), chain -> {
                Object result = chain.proceed();
                return readFromTmp() != null ? Collections.emptySet() : result;
            });
            hook(BluetoothAdapter.class.getDeclaredMethod("startDiscovery"), chain -> false);
        } catch (Throwable ignored) {
        }

        // ==========================================
        // 6. GNSS / SDK compatibility
        // ==========================================
        hookAllMethods(LocationManager.class, "addNmeaListener", chain -> true);

        if (Build.VERSION.SDK_INT >= 24) {
            try {
                hook(GnssStatus.class.getDeclaredMethod("getSatelliteCount"), chain -> {
                    if (readFromTmp() != null) return 15;
                    return chain.proceed();
                });
            } catch (Throwable ignored) {
            }
        }

        try {
            Class<?> amap = Class.forName("com.amap.api.location.AMapLocation", false, classLoader);
            hookSDK(amap, getterHook);
            hookIfPresent(amap, "getLocationType", new Class<?>[0], chain -> 1);
        } catch (Throwable ignored) {
        }

        try {
            Class<?> tencent = Class.forName("com.tencent.map.geolocation.TencentLocation", false, classLoader);
            hookSDK(tencent, getterHook);
            hookIfPresent(tencent, "getProvider", new Class<?>[0], chain -> "gps");
        } catch (Throwable ignored) {
        }
    }

    private void hookSDK(Class<?> clazz, XposedInterface.Hooker hooker) {
        hookIfPresent(clazz, "getLatitude", new Class<?>[0], hooker);
        hookIfPresent(clazz, "getLongitude", new Class<?>[0], hooker);
        hookIfPresent(clazz, "getAccuracy", new Class<?>[0], hooker);
    }

    private void hookAllMethods(Class<?> clazz, String name, XposedInterface.Hooker hooker) {
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getName().equals(name) && !Modifier.isAbstract(method.getModifiers())) {
                try {
                    hook(method, hooker);
                } catch (Throwable t) {
                    log(android.util.Log.WARN, TAG, "Unable to hook " + method, t);
                }
            }
        }
    }

    private void hookIfPresent(Class<?> clazz, String name, Class<?>[] parameterTypes,
                               XposedInterface.Hooker hooker) {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            hook(method, hooker);
        } catch (Throwable ignored) {
        }
    }

    private void hook(java.lang.reflect.Executable executable, XposedInterface.Hooker hooker) {
        hook(executable)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    private Method findMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                // Continue with the superclass and implemented interfaces.
            }
            for (Class<?> iface : current.getInterfaces()) {
                Method method = findMethodInInterface(iface, name, parameterTypes);
                if (method != null) return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private Method findMethodInInterface(Class<?> iface, String name, Class<?>... parameterTypes) {
        try {
            return iface.getDeclaredMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            // Search parent interfaces as well.
        }
        for (Class<?> parent : iface.getInterfaces()) {
            Method method = findMethodInInterface(parent, name, parameterTypes);
            if (method != null) return method;
        }
        return null;
    }

    private void applyLocationFix(Location loc) {
        if (loc == null) return;
        double[] c = readFromTmp();
        if (c != null) {
            double d = getDrift();
            try {
                setField(loc, "mLatitude", c[0] + d);
                setField(loc, "mLongitude", c[1] + d);
            } catch (Throwable t) {
                loc.setLatitude(c[0] + d);
                loc.setLongitude(c[1] + d);
            }
            loc.setAccuracy(3.0f + new Random().nextFloat());
            loc.setProvider(LocationManager.GPS_PROVIDER);
            loc.setSpeed(0.0f);
            loc.setAltitude(50.0d);
            loc.setTime(System.currentTimeMillis());
            if (Build.VERSION.SDK_INT >= 17) {
                loc.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            }
            try {
                setField(loc, "mIsFromMockProvider", false);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void setField(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) return;
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (Throwable ignored) {
        }
    }

    private static Field findField(Class<?> clazz, String name) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private double getDrift() {
        return (new Random().nextDouble() - 0.5) * 0.00002;
    }

    private double[] readFromTmp() {
        try {
            File file = new File(FILE_PATH);
            if (!file.exists() || !file.canRead()) return null;

            RandomAccessFile raf = new RandomAccessFile(file, "r");
            String line = raf.readLine();
            raf.close();
            if (line == null) return null;

            String[] p = line.split(",");
            if (p.length < 3 || !"1".equals(p[2])) return null;

            if (p.length >= 5) {
                cachedMac = p[3];
                cachedSSID = p[4];
            }

            return new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (Exception e) {
            return null;
        }
    }
}
