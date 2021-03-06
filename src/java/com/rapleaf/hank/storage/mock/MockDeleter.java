package com.rapleaf.hank.storage.mock;

import java.io.IOException;

import com.rapleaf.hank.storage.Deleter;

public class MockDeleter implements Deleter {
  private final int partNum;
  private boolean hasDeleted = false;

  public MockDeleter(int partNum) {
    this.partNum = partNum;
  }

  @Override
  public void delete() throws IOException {
    hasDeleted = true;
  }

  public boolean hasDeleted() {
    return hasDeleted;
  }
}
