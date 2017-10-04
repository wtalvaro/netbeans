/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.netbeans.modules.java.source.indexing;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;
import org.netbeans.modules.java.source.indexing.CompileWorker.ParsingOutput;
import org.netbeans.modules.java.source.indexing.JavaCustomIndexer.CompileTuple;
import org.netbeans.modules.parsing.spi.indexing.Context;

/**TODO: this test needs to run with vanilla javac
 *
 * @author lahvac
 */
public class VanillaCompileWorkerTest extends CompileWorkerTestBase {
    
    public VanillaCompileWorkerTest(String name) {
        super(name);
    }
    
    @Override
    protected ParsingOutput runCompileWorker(Context context, JavaParsingContext javaContext, Collection<? extends CompileTuple> files) {
        return new VanillaCompileWorker().compile(null, context, javaContext, files);
    }
    
    public void testVanillaWorker() throws Exception {
        ParsingOutput result = runIndexing(Arrays.asList(compileTuple("test/Test3.java", "package test; public class Test3"),
                                                         compileTuple("test/Test4.java", "package test; public class Test4 { Undef undef; }")),
                                           Arrays.asList(virtualCompileTuple("test/Test1.virtual", "package test; public class Test1 {}"),
                                                         virtualCompileTuple("test/Test2.virtual", "package test; public class Test2 {}")));
        
        assertFalse(result.lowMemory);
        assertTrue(result.success);
        
        Set<String> createdFiles = new HashSet<String>();
        
        for (File created : result.createdFiles) {
            createdFiles.add(getWorkDir().toURI().relativize(created.toURI()).getPath());
        }
        
        //TODO:
//        assertEquals(new HashSet<String>(Arrays.asList("cache/s1/java/15/classes/test/Test3.sig")), createdFiles);
        result = runIndexing(Arrays.asList(compileTuple("test/Test4.java", "package test; public class Test4 { void t() { Undef undef; } }")),
                             Collections.emptyList());
        
        assertFalse(result.lowMemory);
        assertTrue(result.success);
    }
}
