/*
 * Licensed to Crate.io GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.metadata;

import org.elasticsearch.test.ESTestCase;
import io.crate.types.StringType;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamInput;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.core.Is.is;

public class IndexReferenceTest extends ESTestCase {

    @Test
    public void testStreaming() throws Exception {
        RelationName relationName = new RelationName("doc", "test");
        ReferenceIdent referenceIdent = new ReferenceIdent(relationName, "string_col");
        Reference reference = new Reference(referenceIdent, RowGranularity.DOC, StringType.INSTANCE, 1, null);

        ReferenceIdent indexReferenceIdent = new ReferenceIdent(relationName, "index_column");
        IndexReference indexReferenceInfo = new IndexReference(
            2,
            indexReferenceIdent,
            Reference.IndexType.FULLTEXT, List.of(reference), "my_analyzer");

        BytesStreamOutput out = new BytesStreamOutput();
        Reference.toStream(indexReferenceInfo, out);

        StreamInput in = out.bytes().streamInput();
        IndexReference indexReferenceInfo2 = Reference.fromStream(in);

        assertThat(indexReferenceInfo2, is(indexReferenceInfo));
    }

}
