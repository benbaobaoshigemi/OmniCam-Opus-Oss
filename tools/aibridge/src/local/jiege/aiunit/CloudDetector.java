package local.jiege.aiunit;

import android.content.Context;

import com.oplus.aiunit.core.base.FrameDetector;

public final class CloudDetector extends FrameDetector {
    public CloudDetector(Context context, String name) {
        super(context, name);
        setConfigMemoryPool(new int[]{1024, 1024, 3072, 3072, 8192, 8192});
    }
}
