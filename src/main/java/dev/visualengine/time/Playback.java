package dev.visualengine.time;

/** A bounded interactive accumulator. Offline stepping never drops ticks. */
public final class Playback {
    public static final double DT = 1.0 / 120;
    private long tick;
    private double accumulator;
    public long tick() { return tick; }
    public double seconds() { return tick * DT; }
    public void reset() { tick=0; accumulator=0; }
    public int accumulate(double elapsed, double speed, boolean paused) {
        if (paused || speed==0) { accumulator=0; return 0; }
        accumulator += Math.min(.1, Math.max(0, elapsed)) * speed;
        int steps = Math.min(8, (int)(accumulator / DT));
        accumulator -= steps * DT;
        // Interactive slowdown discards excess backlog. Offline export calls advance directly.
        if (accumulator >= DT) accumulator %= DT;
        return steps;
    }
    public void advance() { tick++; }
    public static double phase(double seconds, double rate) {
        return Math.IEEEremainder(seconds*rate, Math.PI*2);
    }
}
