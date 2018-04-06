package com.twiliorn.library;

import org.webrtc.EglBase;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/*
 * Uses reflection to interact with non public class EglBaseProvider.
 */
public class EglBaseProviderReflectionUtils {

    public static Object getEglBaseProvider(Object owner) {
        Object eglBaseProvider = null;
        try {
            Class<?> eglBaseProviderClass = Class.forName("com.twilio.video.EglBaseProvider");
            Method instanceMethod = eglBaseProviderClass.getDeclaredMethod("instance",
                    Object.class);
            instanceMethod.setAccessible(true);
            eglBaseProvider = instanceMethod.invoke(null, owner);
        } catch (Throwable e) {
            e.printStackTrace();
        }

        return eglBaseProvider;
    }

    public static EglBase.Context getRootEglBaseContext(Object eglBaseProvider) {
        EglBase.Context rootEglBaseContext = null;

        try {
            Field rootEglBaseField = eglBaseProvider.getClass().getDeclaredField("rootEglBase");
            rootEglBaseField.setAccessible(true);
            Object rootEglBase = rootEglBaseField.get(eglBaseProvider);
            Method getEglBaseContextMethod = rootEglBase.getClass()
                    .getDeclaredMethod("getEglBaseContext");
            getEglBaseContextMethod.setAccessible(true);
            rootEglBaseContext = (EglBase.Context) getEglBaseContextMethod.invoke(rootEglBase);
        } catch (NoSuchFieldException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }

        return rootEglBaseContext;
    }

    public static void relaseEglBaseProvider(Object eglBaseProvider, Object owner) {
        try {
            Method eglBaseProviderReleaseMethod = eglBaseProvider.getClass()
                    .getDeclaredMethod("release", Object.class);
            eglBaseProviderReleaseMethod.setAccessible(true);
            eglBaseProviderReleaseMethod.invoke(eglBaseProvider, owner);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        }
    }
}