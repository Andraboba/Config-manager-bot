package com.petr.configmanager;

public enum ConfigType {
    WS, XHTTP, BOTH;

    public static ConfigType fromString(String s) {
        return switch (s) {
            case "xhttp" -> XHTTP;
            case "both" -> BOTH;
            default -> WS;
        };
    }

    public String toCallbackData() {
        return switch (this) {
            case XHTTP -> "xhttp";
            case BOTH -> "both";
            case WS -> "ws";
        };
    }

    public boolean includesWs() { return this == WS || this == BOTH; }
    public boolean includesXhttp() { return this == XHTTP || this == BOTH; }
}
