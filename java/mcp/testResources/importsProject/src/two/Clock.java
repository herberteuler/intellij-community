// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package two;

/** Has the member of {@code one.Clock}, but not as a static one. */
public class Clock {
  public Clock start(one.Tick tick) {
    return this;
  }
}
