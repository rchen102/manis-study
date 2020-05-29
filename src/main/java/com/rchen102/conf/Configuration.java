package com.rchen102.conf;

import java.util.Properties;

public class Configuration {
    Properties properties;
    public static final String TRUE_STR = "true";
    public static final String FALSE_STR = "false";

    public Configuration() {
        //TODO init properties
        properties = new Properties();
    }

    public void set(String name, String value) {
        properties.setProperty(name, value);
    }

    public String get(String name) {
        return properties.getProperty(name);
    }

    public String get(String name, String defaultValue) {
        return properties.getProperty(name, defaultValue);
    }

    public int getInt(String name, int defaultValue) {
        String valueStr = get(name);
        if (valueStr == null) {
            return defaultValue;
        }
        return Integer.parseInt(valueStr);
    }

    public boolean getBoolean(String name, boolean defaultValue) {
        String valueStr = get(name);
        if (valueStr == null) {
            return defaultValue;
        }

        valueStr = valueStr.toLowerCase();
        if (TRUE_STR.equals(valueStr)) {
            return true;
        }
        if (FALSE_STR.equals(valueStr)) {
            return false;
        }
        return defaultValue;
    }

    public void setClass(String name, Class<?> theClass, Class<?> xface) {
        if (!xface.isAssignableFrom(theClass)) {
            throw new RuntimeException(theClass + " not " + xface.getName());
        }
        set(name, theClass.getName());
    }

    public Class<?> getClassByName(String clsName) throws ClassNotFoundException {
        return Class.forName(clsName);
    }

    public Class<?> getClass(String name, Class<?> defaultValue) {
        String clsName = get(name);
        if (clsName == null) {
            return defaultValue;
        }

        try {
            return getClassByName(clsName);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
