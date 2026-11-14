package com.voiceprank.app.inject;

interface IShell {
    // Transaction id reserved by Shizuku for UserService teardown.
    void destroy() = 16777114;

    String run(String command, int timeoutMs) = 1;
    int uid() = 2;
}
