package io.github.rthery.devicechecker.shell;

interface ICodecService {
    void destroy() = 16777114;
    String state() = 1;
    String select(int codecType) = 2;
    String reset(in int[] codecTypes) = 3;
}
