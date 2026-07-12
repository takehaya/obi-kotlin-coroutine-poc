package obicoro;

/** JNI bridge. The library is loaded by Track.init(). */
public final class Native {
  private Native() {}

  /** Linux kernel tid (gettid(2)). Not the same thing as Thread.threadId(). */
  public static native int gettid0();

  /**
   * op=3 (java_threads): records "parent of the current tid = parent" in OBI's java_tasks map.
   * Used by the v1 approach; kept for experimentation, unused by the current agent.
   */
  public static native int sendParent0(long parent);

  /** op=4 (java_vt_mount): mounts a logical id on the current tid. OBI substitutes the trace-key tid. */
  public static native int mount0(long id);

  /** op=5 (java_vt_unmount): unmounts from the current tid. */
  public static native int unmount0();
}
