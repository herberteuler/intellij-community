// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
import one.Alpha;

public class CalledArguments {
  void record(Journal journal, Alpha alpha) {
    journal.write(alpha, 2);
  }
}
