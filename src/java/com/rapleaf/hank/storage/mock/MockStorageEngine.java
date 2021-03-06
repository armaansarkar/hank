/**
 *  Copyright 2011 Rapleaf
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package com.rapleaf.hank.storage.mock;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.rapleaf.hank.config.PartservConfigurator;
import com.rapleaf.hank.storage.Deleter;
import com.rapleaf.hank.storage.OutputStreamFactory;
import com.rapleaf.hank.storage.Reader;
import com.rapleaf.hank.storage.StorageEngine;
import com.rapleaf.hank.storage.Updater;
import com.rapleaf.hank.storage.Writer;

public class MockStorageEngine implements StorageEngine {
  public boolean getReaderCalled;

  @Override
  public Reader getReader(PartservConfigurator configurator, int partNum)
  throws IOException {
    getReaderCalled = true;
    return null;
  }

  @Override
  public Updater getUpdater(PartservConfigurator configurator, int partNum) {
    return null;
  }

  @Override
  public Writer getWriter(OutputStreamFactory streamFactory, int partNum,
      int versionNumber, boolean base) throws IOException {
    return null;
  }
  
  @Override
  public Deleter getDeleter(PartservConfigurator configurator, int partNum)
      throws IOException {
    return new MockDeleter(partNum);
  }

  @Override
  public ByteBuffer getComparableKey(ByteBuffer key) {
    return null;
  }
}
