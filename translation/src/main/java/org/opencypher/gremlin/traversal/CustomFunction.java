/*
 * Copyright (c) 2018 "Neo4j, Inc." [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.opencypher.gremlin.traversal;

import static java.lang.Integer.parseInt;
import static java.util.stream.Collectors.toList;
import static org.opencypher.gremlin.translation.Tokens.PROJECTION_ELEMENT;
import static org.opencypher.gremlin.translation.Tokens.PROJECTION_ID;
import static org.opencypher.gremlin.translation.Tokens.PROJECTION_INV;
import static org.opencypher.gremlin.translation.Tokens.PROJECTION_OUTV;
import static org.opencypher.gremlin.translation.Tokens.PROJECTION_RELATIONSHIP;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.apache.tinkerpop.gremlin.process.traversal.Path;
import org.apache.tinkerpop.gremlin.process.traversal.Traverser;
import org.apache.tinkerpop.gremlin.process.traversal.dsl.graph.GraphTraversal;
import org.apache.tinkerpop.gremlin.process.traversal.util.TraversalUtil;
import org.apache.tinkerpop.gremlin.structure.Edge;
import org.apache.tinkerpop.gremlin.structure.Element;
import org.apache.tinkerpop.gremlin.structure.Property;
import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Vertex;
import org.opencypher.gremlin.translation.Tokens;
import org.opencypher.gremlin.translation.exception.TypeException;

@SuppressWarnings("unchecked")
public class CustomFunction implements Function<Traverser, Object> {
    private final String name;
    private final Object[] args;
    private final Function<Traverser, Object> implementation;

    private CustomFunction(String name, Function<Traverser, Object> reference, Object... args) {
        this.name = name;
        this.args = args;
        this.implementation = reference;
    }

    public String getName() {
        return name;
    }

    public Object[] getArgs() {
        return args;
    }

    @Override
    public Object apply(Traverser traverser) {
        return implementation.apply(traverser);
    }

    public static CustomFunction length() {
        return new CustomFunction(
            "length",
            traverser -> (((Path) traverser.get()).size() - 1) / 2
        );
    }

    public static CustomFunction convertToString() {
        return new CustomFunction(
            "convertToString",
            traverser -> Optional.ofNullable(traverser.get())
                .map(String::valueOf)
                .orElse(null)
        );
    }

    public static CustomFunction convertToBoolean() {
        return new CustomFunction(
            "convertToBoolean",
            traverser -> {
                Object arg = tokenToNull(traverser.get());
                boolean valid = arg == null ||
                    arg instanceof Boolean ||
                    arg instanceof String;
                if (!valid) {
                    String className = arg.getClass().getName();
                    throw new TypeException("Cannot convert " + className + " to boolean");
                }

                return Optional.ofNullable(arg)
                    .map(String::valueOf)
                    .map(v -> {
                        switch (v.toLowerCase()) {
                            case "true":
                                return true;
                            case "false":
                                return false;
                            default:
                                return Tokens.NULL;
                        }
                    })
                    .orElse(Tokens.NULL);
            });
    }

    public static CustomFunction convertToIntegerType() {
        return new CustomFunction(
            "convertToIntegerType",
            traverser -> {
                Object arg = tokenToNull(traverser.get());
                // long in org.neo4j.driver.internal.value.IntegerValue#val
                Long integer = convertToLong(arg);
                return nullToToken(integer);
            });
    }

    public static CustomFunction convertToFloat() {
        return new CustomFunction(
            "convertToFloat",
            traverser -> {
                Object arg = tokenToNull(traverser.get());
                boolean valid = arg == null ||
                    arg instanceof Number ||
                    arg instanceof String;
                if (!valid) {
                    String className = arg.getClass().getName();
                    throw new TypeException("Cannot convert " + className + " to float");
                }

                return nullToToken(
                    Optional.ofNullable(arg)
                        .map(String::valueOf)
                        .map(v -> {
                            try {
                                return Double.valueOf(v);
                            } catch (NumberFormatException e) {
                                return null;
                            }
                        })
                        .orElse(null));
            });
    }

    public static CustomFunction properties() {
        return new CustomFunction(
            "properties",
            traverser -> {
                Iterator<? extends Property<Object>> it = ((Element) traverser.get()).properties();
                Map<Object, Object> propertyMap = new HashMap<>();
                while (it.hasNext()) {
                    Property<Object> property = it.next();
                    propertyMap.putIfAbsent(property.key(), property.value());
                }
                return propertyMap;
            });
    }

    public static CustomFunction nodes() {
        return new CustomFunction(
            "nodes",
            traverser -> ((Path) traverser.get()).objects().stream()
                .filter(element -> element instanceof Vertex)
                .map(CustomFunction::finalizeElements)
                .collect(toList()));
    }

    public static CustomFunction relationships() {
        return new CustomFunction(
            "relationships",
            traverser -> ((Collection) ((Path) traverser.get()).objects()).stream()
                .flatMap(CustomFunction::flatten)
                .filter(element -> element instanceof Edge)
                .map(CustomFunction::finalizeElements)
                .collect(toList()));
    }

    public static CustomFunction listComprehension(final Object functionTraversal) {
        return new CustomFunction(
            "listComprehension",
            traverser -> {
                Object list = traverser.get();
                if (!(list instanceof Collection)) {
                    throw new IllegalArgumentException("Expected Iterable, got " + list.getClass());
                }

                if (!(functionTraversal instanceof GraphTraversal)) {
                    throw new IllegalArgumentException("Expected GraphTraversal, got " + list.getClass());
                }

                return ((Collection) list)
                    .stream()
                    .map(item -> {
                        GraphTraversal.Admin admin = GraphTraversal.class.cast(functionTraversal).asAdmin();
                        return TraversalUtil.apply(item, admin);
                    })
                    .collect(toList());
            },
            functionTraversal);
    }

    public static CustomFunction pathComprehension() {
        return new CustomFunction(
            "pathComprehension",
            arg -> ((Collection) arg.get()).stream()
                .map(CustomFunction::pathToList)
                .map(path -> {
                    Optional<Edge> first = ((Collection) path)
                        .stream()
                        .filter(Edge.class::isInstance)
                        .map(Edge.class::cast)
                        .findFirst();

                    Edge edge = first.orElseThrow(() -> new RuntimeException("Invalid path, no edge found!"));

                    HashMap<String, Object> result = new HashMap<>();

                    HashMap<String, Object> projectionRelationship = new HashMap<>();
                    projectionRelationship.put(PROJECTION_ID, edge.id());
                    projectionRelationship.put(PROJECTION_INV, edge.inVertex().id());
                    projectionRelationship.put(PROJECTION_OUTV, edge.outVertex().id());

                    result.put(PROJECTION_RELATIONSHIP, Arrays.asList(projectionRelationship));

                    result.put(PROJECTION_ELEMENT,
                        Stream.of(
                            edge.outVertex(),
                            edge,
                            edge.inVertex())
                            .map(CustomFunction::valueMap)
                            .collect(toList()));

                    return result;
                })
                .collect(toList()));
    }

    public static CustomFunction containerIndex(Object index) {
        return new CustomFunction(
            "containerIndex",
            traverser -> {
                Object arg = traverser.get();
                if (arg instanceof Map) {
                    Map map = (Map) arg;
                    return map.get(index);
                }
                Collection coll = (Collection) arg;
                int idx = parseInt(String.valueOf(index));
                return coll.stream()
                    .skip(idx)
                    .findFirst()
                    .orElse(null);
            },
            index);
    }

    public static CustomFunction percentileCont(double percentile) {
        return new CustomFunction(
            "percentileCont",
            percentileFunction(
                percentile,
                data -> {
                    int last = data.size() - 1;
                    double lowPercentile = Math.floor(percentile * last) / last;
                    double highPercentile = Math.ceil(percentile * last) / last;
                    if (lowPercentile == highPercentile) {
                        return percentileNearest(data, percentile);
                    }

                    double scale = (percentile - lowPercentile) / (highPercentile - lowPercentile);
                    double low = percentileNearest(data, lowPercentile).doubleValue();
                    double high = percentileNearest(data, highPercentile).doubleValue();
                    return (high - low) * scale + low;
                }
            ),
            percentile
        );
    }

    public static CustomFunction percentileDisc(double percentile) {
        return new CustomFunction(
            "percentileDisc",
            percentileFunction(
                percentile,
                data -> percentileNearest(data, percentile)
            ),
            percentile
        );
    }

    private static Function<Traverser, Object> percentileFunction(double percentile,
                                                                  Function<List<Number>, Number> percentileStrategy) {
        return traverser -> {
            if (percentile < 0 || percentile > 1) {
                throw new IllegalArgumentException("Number out of range: " + percentile);
            }

            Collection<?> coll = (Collection<?>) traverser.get();
            boolean invalid = coll.stream()
                .anyMatch(o -> !(o == null || o instanceof Number));
            if (invalid) {
                throw new IllegalArgumentException("Percentile function can only handle numerical values");
            }
            List<Number> data = coll.stream()
                .filter(Objects::nonNull)
                .map(o -> (Number) o)
                .sorted()
                .collect(toList());

            int size = data.size();
            if (size == 0) {
                return Tokens.NULL;
            } else if (size == 1) {
                return data.get(0);
            }

            return percentileStrategy.apply(data);
        };
    }

    private static <T> T percentileNearest(List<T> sorted, double percentile) {
        int size = sorted.size();
        int index = (int) Math.ceil(percentile * size) - 1;
        if (index == -1) {
            index = 0;
        }
        return sorted.get(index);
    }

    public static CustomFunction size() {
        return new CustomFunction(
            "size", traverser -> traverser.get() instanceof String ?
            (long) ((String) traverser.get()).length() :
            (long) ((Collection) traverser.get()).size());
    }

    static Long convertToLong(Object arg) {
        boolean valid = arg == null ||
            arg instanceof Number ||
            arg instanceof String;
        if (!valid) {
            String className = arg.getClass().getName();
            throw new TypeException("Cannot convert " + className + " to integer");
        }

        return Optional.ofNullable(arg)
            .map(String::valueOf)
            .map(v -> {
                try {
                    return Double.valueOf(v);
                } catch (NumberFormatException e) {
                    return null;
                }
            })
            .map(Double::longValue)
            .orElse(null);
    }

    private static Object flatten(Object element) {
        return element instanceof Collection ? ((Collection) element).stream() : Stream.of(element);
    }

    private static Object tokenToNull(Object maybeNull) {
        return Tokens.NULL.equals(maybeNull) ? null : maybeNull;
    }

    private static Object nullToToken(Object maybeNull) {
        return maybeNull == null ? Tokens.NULL : maybeNull;
    }

    private static Object pathToList(Object value) {
        return value instanceof Path ? new ArrayList<>(((Path) value).objects()) : value;
    }

    private static Object finalizeElements(Object o) {

        if (Tokens.NULL.equals(o)) {
            return Tokens.NULL;
        }

        if (o instanceof Vertex) {
            return valueMap((Element) o);
        } else {
            Edge edge = (Edge) o;

            HashMap<Object, Object> wrapper = new HashMap<>();
            wrapper.put(PROJECTION_INV, edge.inVertex().id());
            wrapper.put(PROJECTION_OUTV, edge.outVertex().id());
            wrapper.put(PROJECTION_ELEMENT, valueMap((Element) o));

            return wrapper;
        }
    }

    private static HashMap<Object, Object> valueMap(Element element) {
        HashMap<Object, Object> result = new HashMap<>();
        result.put(T.id, element.id());
        result.put(T.label, element.label());
        element.properties().forEachRemaining(e -> result.put(e.key(), e.value()));
        return result;
    }
}