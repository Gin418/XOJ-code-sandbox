package com.oj.codesandbox;

import java.io.File;

/**
 * C++ 代码沙箱模板：定义 C++ 语言的代码文件名、编译命令和运行命令，其余流程复用公共模板。
 * 依赖本机安装的 g++ 编译器（MinGW-w64）
 */
public abstract class CppCodeSandboxTemplate extends CodeSandboxTemplate {

    private static final String GLOBAL_CPP_FILE_NAME = "main.cpp";

    private static final String GLOBAL_CPP_EXE_NAME = "main.exe";

    @Override
    protected String getCodeFileName() {
        return GLOBAL_CPP_FILE_NAME;
    }

    @Override
    protected String getCompileCmd(File userCodeFile) {
        String parentPath = userCodeFile.getParentFile().getAbsolutePath();
        String exePath = parentPath + File.separator + GLOBAL_CPP_EXE_NAME;
        /**
         * g++: 编译 C++ 代码的命令。
         * -std=c++17: 使用 C++17 标准。
         * -finput-charset=UTF-8: 指定源文件编码为 UTF-8。
         * -fexec-charset=UTF-8: 指定程序运行时输出编码为 UTF-8，保证中文输出不乱码。
         * -O2: 开启 O2 优化，与 OJ 评测环境一致。
         * -o exePath: 指定生成的可执行文件路径。
         */
        return String.format("g++ -std=c++17 -finput-charset=UTF-8 -fexec-charset=UTF-8 -O2 -o %s %s",
                exePath, userCodeFile.getAbsolutePath());
    }

    @Override
    protected String getRunCmd(File userCodeFile, Long memoryLimit) {
        String parentPath = userCodeFile.getParentFile().getAbsolutePath();
        // C++ 无堆上限，内存限制由公共模板的内存采样线程检测
        return parentPath + File.separator + GLOBAL_CPP_EXE_NAME;
    }
}
