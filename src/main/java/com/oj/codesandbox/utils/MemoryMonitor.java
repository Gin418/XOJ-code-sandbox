package com.oj.codesandbox.utils;

import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.BaseTSD;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * Windows 进程内存监控工具类
 *
 * 通过 JNA 调用 Win32 API（psapi.dll 的 GetProcessMemoryInfo）获取进程峰值工作集。
 * 峰值工作集（PeakWorkingSetSize）由操作系统内核维护、单调不减，进程存活期间
 * 任意时刻查询都能拿到自启动以来的最大内存占用，无需高频精确采样。
 */
public class MemoryMonitor {

    /**
     * psapi.dll 扩展接口：jna-platform 内置的 Psapi 未声明 GetProcessMemoryInfo，需自行定义
     */
    private interface PsapiExt extends StdCallLibrary {

        PsapiExt INSTANCE = Native.load("psapi", PsapiExt.class, W32APIOptions.DEFAULT_OPTIONS);

        boolean GetProcessMemoryInfo(WinNT.HANDLE process, PROCESS_MEMORY_COUNTERS counters, int cb);

        @Structure.FieldOrder({"cb", "PageFaultCount", "PeakWorkingSetSize", "WorkingSetSize",
                "QuotaPeakPagedPoolUsage", "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage"})
        class PROCESS_MEMORY_COUNTERS extends Structure {
            public int cb;
            public int PageFaultCount;
            public BaseTSD.SIZE_T PeakWorkingSetSize;
            public BaseTSD.SIZE_T WorkingSetSize;
            public BaseTSD.SIZE_T QuotaPeakPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaPeakNonPagedPoolUsage;
            public BaseTSD.SIZE_T QuotaNonPagedPoolUsage;
            public BaseTSD.SIZE_T PagefileUsage;
            public BaseTSD.SIZE_T PeakPagefileUsage;
        }
    }

    /**
     * 查询进程的峰值工作集内存
     *
     * @param pid 进程 ID
     * @return 峰值内存（KB），查询失败返回 null
     */
    public static Long getProcessMemory(long pid) {
        // 打开进程句柄，查询内存信息需要这两个权限
        WinNT.HANDLE handle = Kernel32.INSTANCE.OpenProcess(
                WinNT.PROCESS_QUERY_INFORMATION | WinNT.PROCESS_VM_READ, false, (int) pid);
        if (handle == null) {
            return null;
        }
        try {
            PsapiExt.PROCESS_MEMORY_COUNTERS counters = new PsapiExt.PROCESS_MEMORY_COUNTERS();
            if (PsapiExt.INSTANCE.GetProcessMemoryInfo(handle, counters, counters.size())) {
                // PeakWorkingSetSize 单位是字节，转换为 KB
                return counters.PeakWorkingSetSize.longValue() / 1024;
            }
            return null;
        } finally {
            Kernel32.INSTANCE.CloseHandle(handle);
        }
    }
}
