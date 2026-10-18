package io.github.rthery.devicechecker.shell;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 1;
}
