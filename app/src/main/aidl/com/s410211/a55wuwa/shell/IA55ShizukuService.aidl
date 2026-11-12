package com.s410211.a55wuwa.shell;

interface IA55ShizukuService {
    // Reserved Shizuku UserService destroy transaction.
    void destroy() = 16777114;
    void exit() = 1;

    // Returns [status, rawValue, detail]. nodeId must be in ShizukuNodeCatalog.
    String[] readNode(int nodeId) = 2;

    // Returns [uid, pid, SELinux context].
    String[] getIdentity() = 3;
}