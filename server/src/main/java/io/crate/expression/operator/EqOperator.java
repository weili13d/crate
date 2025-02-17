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

package io.crate.expression.operator;

import static io.crate.lucene.LuceneQueryBuilder.genericFunctionFilter;
import static io.crate.metadata.functions.TypeVariableConstraint.typeVariable;
import static io.crate.types.TypeSignature.parseTypeSignature;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.FloatPoint;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.elasticsearch.common.lucene.BytesRefs;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.index.mapper.Uid;

import io.crate.data.Input;
import io.crate.expression.symbol.Function;
import io.crate.expression.symbol.Literal;
import io.crate.expression.symbol.Symbol;
import io.crate.lucene.LuceneQueryBuilder;
import io.crate.lucene.LuceneQueryBuilder.Context;
import io.crate.metadata.NodeContext;
import io.crate.metadata.Reference;
import io.crate.metadata.Reference.IndexType;
import io.crate.metadata.TransactionContext;
import io.crate.metadata.doc.DocSysColumns;
import io.crate.metadata.functions.Signature;
import io.crate.types.ArrayType;
import io.crate.types.ByteType;
import io.crate.types.DataType;
import io.crate.types.DoubleType;
import io.crate.types.FloatType;
import io.crate.types.IntegerType;
import io.crate.types.LongType;
import io.crate.types.ObjectType;
import io.crate.types.ShortType;
import io.crate.types.StringType;

public final class EqOperator extends Operator<Object> {

    public static final String NAME = "op_=";

    public static final Signature SIGNATURE = Signature.scalar(
        NAME,
        parseTypeSignature("E"),
        parseTypeSignature("E"),
        Operator.RETURN_TYPE.getTypeSignature()
    ).withTypeVariableConstraints(typeVariable("E"));

    public static void register(OperatorModule module) {
        module.register(
            SIGNATURE,
            EqOperator::new
        );
    }

    private final Signature signature;
    private final Signature boundSignature;


    private EqOperator(Signature signature, Signature boundSignature) {
        this.signature = signature;
        this.boundSignature = boundSignature;
    }

    @Override
    public Boolean evaluate(TransactionContext txnCtx, NodeContext nodeCtx, Input<Object>[] args) {
        assert args.length == 2 : "number of args must be 2";
        Object left = args[0].value();
        if (left == null) {
            return null;
        }
        Object right = args[1].value();
        if (right == null) {
            return null;
        }
        return left.equals(right);
    }

    @Override
    public Signature signature() {
        return signature;
    }

    @Override
    public Signature boundSignature() {
        return boundSignature;
    }

    @Override
    public Query toQuery(Function function, Context context) {
        List<Symbol> args = function.arguments();
        if (!(args.get(0) instanceof Reference ref && args.get(1) instanceof Literal<?> literal)) {
            return null;
        }
        String fqn = ref.column().fqn();
        Object value = literal.value();
        if (value == null) {
            return Queries.newMatchNoDocsQuery("`" + fqn + "` = null is always null");
        }
        DataType<?> dataType = ref.valueType();
        if (dataType.id() != ObjectType.ID && ref.indexType() == IndexType.NONE) {
            throw new IllegalArgumentException(
                "Cannot search on field [" + fqn + "] since it is not indexed.");
        }
        return switch (dataType.id()) {
            case ObjectType.ID -> refEqObject(function, fqn, (ObjectType) dataType, (Map<String, Object>) value, context);
            case ArrayType.ID -> refEqArray(function, fqn, (Collection) value, context);
            default -> fromPrimitive(dataType, fqn, value);
        };
    }

    private Query refEqArray(Function function, String column, Collection<?> values, LuceneQueryBuilder.Context context) {
        List<?> list = values.stream().filter(Objects::nonNull).toList();
        if (list.isEmpty()) {
            return genericFunctionFilter(function, context);
        }
        MappedFieldType fieldType = context.getFieldTypeOrNull(column);
        if (fieldType == null) {
            // field doesn't exist, can't match
            return Queries.newMatchNoDocsQuery("column does not exist in this index");
        }
        Query termsQuery = fieldType.termsQuery(list, context.queryShardContext());

        // wrap boolTermsFilter and genericFunction filter in an additional BooleanFilter to control the ordering of the filters
        // termsFilter is applied first
        // afterwards the more expensive genericFunctionFilter
        BooleanQuery.Builder filterClauses = new BooleanQuery.Builder();
        filterClauses.add(termsQuery, BooleanClause.Occur.MUST);
        filterClauses.add(genericFunctionFilter(function, context), BooleanClause.Occur.MUST);
        return filterClauses.build();
    }

    private static Query fromPrimitive(DataType<?> type, String column, Object value) {
        if (column.equals(DocSysColumns.ID.name())) {
            return new TermQuery(new Term(column, Uid.encodeId((String) value)));
        }
        return switch (type.id()) {
            case ByteType.ID, ShortType.ID, IntegerType.ID -> IntPoint.newExactQuery(column, ((Number) value).intValue());
            case LongType.ID -> LongPoint.newExactQuery(column, (long) value);
            case FloatType.ID -> FloatPoint.newExactQuery(column, (float) value);
            case DoubleType.ID -> DoublePoint.newExactQuery(column, (double) value);
            case StringType.ID -> new TermQuery(new Term(column, BytesRefs.toBytesRef(value)));
            default -> null;
        };
    }


    /**
     * Query for object columns that tries to utilize efficient termQueries for the objects children.
     * <pre>
     * {@code
     *      // If x and y are known columns
     *      o = {x=10, y=20}    -> o.x=10 and o.y=20
     *
     *      // Only x is known:
     *      o = {x=10, y=20}    -> o.x=10 and generic(o == {x=10, y=20})
     *
     *      // No column is known:
     *      o = {x=10, y=20}    -> generic(o == {x=10, y=20})
     * }
     * </pre>
     **/
    private static Query refEqObject(Function eq,
                                     String fqn,
                                     ObjectType type,
                                     Map<String, Object> value,
                                     LuceneQueryBuilder.Context context) {
        BooleanQuery.Builder boolBuilder = new BooleanQuery.Builder();
        int preFilters = 0;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            String key = entry.getKey();
            DataType<?> innerType = type.innerType(key);
            if (innerType == null) {
                // could be a nested object or not part of meta data; skip pre-filtering
                continue;
            }
            String fqNestedColumn = fqn + '.' + key;
            Query innerQuery = fromPrimitive(innerType, fqNestedColumn, entry.getValue());
            if (innerQuery == null) {
                continue;
            }

            preFilters++;
            boolBuilder.add(innerQuery, BooleanClause.Occur.MUST);
        }
        if (preFilters == value.size()) {
            return boolBuilder.build();
        } else {
            Query genericEqFilter = genericFunctionFilter(eq, context);
            if (preFilters == 0) {
                return genericEqFilter;
            } else {
                boolBuilder.add(genericFunctionFilter(eq, context), BooleanClause.Occur.FILTER);
                return boolBuilder.build();
            }
        }
    }
}
