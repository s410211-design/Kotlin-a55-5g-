package com.s410211.a55wuwa.shell;

interface IA55ShizukuService {
    // Reserved Shizuku UserService destroy transaction.
    void destroy() = 16777114;
    void exit() = 1;

    // Returns [status, rawValue, detail]. nodeId must be in ShizukuNode.
    String[] readNode(int nodeId) = 2;

    // Returns [uid, pid, SELinux context].
    String[] getIdentity() = 3;

    // Writes only explicitly allowlisted, policy-validated controls.
    String[] writeNode(int nodeId, String value, String expectedCurrentValue) = 4;

    // Query and change only Android GameManager modes for a validated installed game.
    String[] getGameMode(String packageName) = 8;
    String[] setGameMode(String packageName, String mode) = 9;
    
    // Fixed Android GameManager backbuffer downscale controls; package and ratio are validated.
    String[] getGameModeConfig(String packageName) = 10;
    String[] setGameModeDownscale(String packageName, String mode, String scale) = 11;
    String[] resetGameModeConfig(String packageName, String mode) = 12;

    // Fixed SurfaceFlinger timestats commands; packageName is validated, never shell-interpolated.
    String[] startSurfaceFlingerTimeStats(String packageName) = 5;
    String[] dumpSurfaceFlingerTimeStats(String packageName) = 6;
    String[] stopSurfaceFlingerTimeStats() = 7;
}