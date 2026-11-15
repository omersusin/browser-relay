package com.browserrelay.app;

interface IShellService {
    String exec(String command);
    void destroy();
}
