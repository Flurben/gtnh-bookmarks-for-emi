package com.emicraftingtree.selftest;

import java.lang.management.ManagementFactory;

import it.unimi.dsi.fastutil.longs.LongArrayList;

/**
 * Dev-only: per-frame timings for the benchmark, fed by its mixins. A frame is the whole of
 * {@code GameRenderer.render} (screen, world behind it, GUI flush); the screen part is Forge's {@code drawScreen},
 * which holds the screen, EMI's sidebars and everything mods draw over them.
 */
public final class FrameTimer {

    private static final com.sun.management.ThreadMXBean THREADS = (com.sun.management.ThreadMXBean) ManagementFactory
            .getThreadMXBean();

    public static final LongArrayList FRAME_NANOS = new LongArrayList();
    public static final LongArrayList SCREEN_NANOS = new LongArrayList();
    public static final LongArrayList SCREEN_BYTES = new LongArrayList();

    private static boolean recording;
    private static boolean markNext;
    private static long frameStart;
    private static long screenStart;
    private static long screenAllocStart;
    private static long screenNanos;

    /** The frame and screen time of the first frame after {@link #markNext()}, or -1 until it was drawn. */
    public static long markedFrameNanos = -1;
    public static long markedScreenNanos = -1;

    private FrameTimer() {}

    public static void start() {
        FRAME_NANOS.clear();
        SCREEN_NANOS.clear();
        SCREEN_BYTES.clear();
        recording = true;
    }

    public static void stop() {
        recording = false;
    }

    public static void markNext() {
        markNext = true;
        markedFrameNanos = -1;
        markedScreenNanos = -1;
    }

    public static void frameStart() {
        frameStart = System.nanoTime();
        screenNanos = -1;
    }

    public static void frameEnd() {
        final long nanos = System.nanoTime() - frameStart;

        if (screenNanos < 0) {
            return;
        }

        if (recording) {
            FRAME_NANOS.add(nanos);
        }

        if (markNext) {
            markNext = false;
            markedFrameNanos = nanos;
            markedScreenNanos = screenNanos;
        }
    }

    public static void screenStart() {
        screenAllocStart = THREADS.getCurrentThreadAllocatedBytes();
        screenStart = System.nanoTime();
    }

    public static void screenEnd() {
        screenNanos = System.nanoTime() - screenStart;

        if (recording) {
            SCREEN_NANOS.add(screenNanos);
            SCREEN_BYTES.add(THREADS.getCurrentThreadAllocatedBytes() - screenAllocStart);
        }
    }

}
