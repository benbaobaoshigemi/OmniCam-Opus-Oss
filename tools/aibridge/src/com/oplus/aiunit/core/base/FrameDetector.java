package com.oplus.aiunit.core.base;

import android.content.Context;

/** 编译占位：运行时由相机进程里的真实类提供 */
public abstract class FrameDetector {
    public FrameDetector(Context context, String name) {
    }

    public Object createInputSlot() {
        return null;
    }

    public Object createOutputSlot() {
        return null;
    }

    public void setConfigMemoryPool(int[] pool) {
    }
}
