package io.github.baljinnyamday.xyp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.lang.module.ModuleDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * The generated service groups, checked by reflection against the generated registry: every
 * operation XYP lists is reachable through exactly one typed method, and every typed method reaches
 * the operation and the endpoint the registry names.
 */
class GeneratedClientsTest {

  private static final Pattern OPERATION_ELEMENT = Pattern.compile("<soap:Body><tns:([^ >/]+)>");
  private static final Pattern ENDPOINT_PATH = Pattern.compile("^/([^/]+)/ws$");

  /** One typed method, and the operation and endpoint it was seen to call. */
  record Reached(Method method, String operation, String endpoint) {}

  private static FakeXyp fake;
  private static XypClient client;

  /** Operation name to the typed methods that reach it, both overloads included. */
  private static Map<String, List<Reached>> reached;

  @BeforeAll
  static void callEveryGeneratedMethodOnce() throws Exception {
    fake =
        FakeXyp.start(
            request ->
                request.method().equals("GET")
                    // Endpoints without a checked-in WSDL have their namespace read once.
                    ? FakeXyp.Reply.ok("<definitions targetNamespace=\"http://fake.xyp.gov.mn/\"/>")
                    : FakeXyp.Reply.ok(FakeXyp.soapResponse("<unused>1</unused>", 0, "ok")));
    client = connect(fake.baseUrl());
    reached = new TreeMap<>();
    for (Method accessor : groupAccessors()) {
      Object group = accessor.invoke(client);
      for (Method method : serviceMethods(group.getClass())) {
        int before = fake.recorded().size();
        method.invoke(group, arguments(method, null, CallOptions.none()));
        FakeXyp.Recorded post = lastPost(fake.recorded().subList(before, fake.recorded().size()));
        Matcher operation = OPERATION_ELEMENT.matcher(post.body());
        Matcher endpoint = ENDPOINT_PATH.matcher(post.uri());
        assertTrue(operation.find(), method + " sent no operation element");
        assertTrue(endpoint.matches(), method + " posted to " + post.uri());
        reached
            .computeIfAbsent(operation.group(1), name -> new ArrayList<>())
            .add(new Reached(method, operation.group(1), endpoint.group(1)));
      }
    }
  }

  @AfterAll
  static void stop() {
    client.close();
    fake.close();
  }

  static XypClient connect(String baseUrl) {
    return XypClient.builder()
        .accessToken("test-access-token")
        .privateKey(TestKeys.RSA.getPrivate())
        .baseUrl(baseUrl)
        .logger(new RecordingLogger())
        .env(name -> null)
        .build();
  }

  /** The group accessors XypClient inherits: citizen(), health(), ... */
  static List<Method> groupAccessors() {
    return Arrays.stream(XypGroups.class.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !Modifier.isStatic(method.getModifiers()))
        .filter(method -> method.getParameterCount() == 0)
        .sorted(Comparator.comparing(Method::getName))
        .toList();
  }

  /** The public service methods of one group client, both overloads of each. */
  static List<Method> serviceMethods(Class<?> group) {
    return Arrays.stream(group.getDeclaredMethods())
        .filter(method -> Modifier.isPublic(method.getModifiers()))
        .filter(method -> !Modifier.isStatic(method.getModifiers()) && !method.isSynthetic())
        .sorted(Comparator.comparing(Method::getName).thenComparing(Method::getParameterCount))
        .toList();
  }

  /** The typed method that takes CallOptions for every operation, keyed by operation name. */
  static Map<String, Method> methodsByOperation() {
    Map<String, Method> methods = new TreeMap<>();
    reached.forEach(
        (operation, calls) ->
            calls.stream()
                .map(Reached::method)
                .filter(
                    method ->
                        method.getParameterCount() > 0
                            && method.getParameterTypes()[method.getParameterCount() - 1]
                                == CallOptions.class)
                .forEach(method -> methods.put(operation, method)));
    return methods;
  }

  static Object[] arguments(Method method, Object params, CallOptions options) {
    Object[] arguments = new Object[method.getParameterCount()];
    Class<?>[] types = method.getParameterTypes();
    for (int i = 0; i < arguments.length; i++) {
      if (types[i] == CallOptions.class) {
        arguments[i] = options;
      } else if (RequestParams.class.isAssignableFrom(types[i])) {
        arguments[i] = params;
      } else {
        fail(method + " takes a " + types[i].getName());
      }
    }
    return arguments;
  }

  private static FakeXyp.Recorded lastPost(List<FakeXyp.Recorded> requests) {
    for (int i = requests.size() - 1; i >= 0; i--) {
      if (requests.get(i).method().equals("POST")) {
        return requests.get(i);
      }
    }
    throw new AssertionError("no POST among " + requests);
  }

  @Test
  void everyRegistryOperationIsReachedByATypedMethod() {
    assertEquals(
        new TreeSet<>(Registry.OPERATION_ENDPOINTS.keySet()),
        reached.keySet(),
        "operations in the registry and operations the generated methods call");
  }

  @Test
  void everyOperationHasExactlyOneMethodWithAndWithoutCallOptions() {
    reached.forEach(
        (operation, calls) -> {
          Set<String> names =
              calls.stream().map(call -> call.method().getName()).collect(Collectors.toSet());
          assertEquals(1, names.size(), operation + " is reached by " + names);
          List<Integer> arities =
              calls.stream().map(call -> call.method().getParameterCount()).sorted().toList();
          int withoutOptions = arities.get(0);
          assertEquals(
              List.of(withoutOptions, withoutOptions + 1),
              arities,
              operation + ": one overload without CallOptions and one with it");
        });
  }

  @Test
  void everyMethodPostsToTheEndpointTheRegistryNames() {
    reached.forEach(
        (operation, calls) -> {
          for (Reached call : calls) {
            assertEquals(
                Registry.OPERATION_ENDPOINTS.get(operation), call.endpoint(), call.toString());
          }
        });
  }

  @Test
  void everyMethodLivesInThePackageOfItsEndpoint() {
    reached.forEach(
        (operation, calls) -> {
          String endpoint = Registry.OPERATION_ENDPOINTS.get(operation);
          String group = endpoint.replaceFirst("-\\d+(\\.\\d+)*$", "").replace("-", "");
          for (Reached call : calls) {
            assertEquals(
                "io.github.baljinnyamday.xyp." + group,
                call.method().getDeclaringClass().getPackageName(),
                operation);
          }
        });
  }

  @Test
  void methodsAreNamedAfterTheOperationWithoutItsCode() {
    reached.forEach(
        (operation, calls) -> {
          String code = operation.substring(0, operation.indexOf('_'));
          String base = decapitalize(operation.substring(operation.indexOf('_') + 1));
          String name = calls.get(0).method().getName();
          assertTrue(
              name.equals(base) || name.equals(base + code),
              operation + " is " + name + ", want " + base + " (or " + base + code + ")");
        });
  }

  @Test
  void theRegistryHas499OperationsIn16Groups() {
    // The same numbers as every other SDK in this repository; a change here is a spec change.
    assertEquals(499, Registry.OPERATION_ENDPOINTS.size());
    assertEquals(16, groupAccessors().size());
  }

  @Test
  void everyGroupAccessorReturnsTheSameInstanceEveryTime() throws Exception {
    try (XypClient other = connect(fake.baseUrl())) {
      for (Method accessor : groupAccessors()) {
        Object first = accessor.invoke(client);
        assertNotNull(first, accessor.getName());
        assertSame(first, accessor.invoke(client), accessor.getName());
        assertNotSame(first, accessor.invoke(other), accessor.getName() + " of another client");
      }
    }
  }

  @Test
  void groupAccessorsAreNamedAfterTheirPackage() {
    for (Method accessor : groupAccessors()) {
      Class<?> group = accessor.getReturnType();
      String packageName = group.getPackageName();
      assertEquals(
          "io.github.baljinnyamday.xyp." + accessor.getName().toLowerCase(Locale.ROOT),
          packageName);
      assertEquals(
          Character.toUpperCase(accessor.getName().charAt(0))
              + accessor.getName().substring(1)
              + "Client",
          group.getSimpleName());
    }
  }

  @Test
  void theModuleExportsTheCoreAndEveryGroupPackage() throws Exception {
    Set<String> want = new TreeSet<>();
    want.add("io.github.baljinnyamday.xyp");
    for (Method accessor : groupAccessors()) {
      want.add(accessor.getReturnType().getPackageName());
    }
    assertEquals(17, want.size());

    Module module = XypClient.class.getModule();
    Set<String> exported;
    if (module.isNamed()) {
      ModuleDescriptor descriptor = module.getDescriptor();
      assertEquals("io.github.baljinnyamday.xyp", descriptor.name());
      assertTrue(
          descriptor.exports().stream().noneMatch(ModuleDescriptor.Exports::isQualified),
          "every export is unqualified");
      exported =
          descriptor.exports().stream()
              .map(ModuleDescriptor.Exports::source)
              .collect(Collectors.toCollection(TreeSet::new));
      Set<String> requires =
          descriptor.requires().stream()
              .map(ModuleDescriptor.Requires::name)
              .collect(Collectors.toCollection(TreeSet::new));
      assertEquals(Set.of("java.base", "java.net.http", "java.xml"), requires);
    } else {
      // On the class path there is no descriptor; read the source instead.
      String source = Files.readString(Path.of("src", "main", "java", "module-info.java"));
      exported = new TreeSet<>();
      Matcher export = Pattern.compile("exports ([\\w.]+);").matcher(source);
      while (export.find()) {
        exported.add(export.group(1));
      }
    }
    assertEquals(want, exported);
  }

  @Test
  void everyParamsClassListsItsFieldsInDeclarationOrder() throws Exception {
    int checked = 0;
    for (Method method : methodsByOperation().values()) {
      if (method.getParameterCount() != 2) {
        continue;
      }
      Class<?> paramsType = method.getParameterTypes()[0];
      Map<String, String> wireNames = ParamsProbe.wireNamesBySetter(paramsType);
      // getDeclaredFields() lists fields in source order on every JDK this is tested on.
      List<String> declared =
          Arrays.stream(paramsType.getDeclaredFields())
              .filter(field -> !Modifier.isStatic(field.getModifiers()))
              .map(field -> wireNames.get(field.getName()))
              .toList();
      List<String> sent =
          ParamsProbe.build(paramsType, Map.of()).toParams().entries().stream()
              .map(Map.Entry::getKey)
              .toList();
      assertEquals(declared, sent, paramsType.getName());
      checked++;
    }
    assertTrue(checked > 400, "only " + checked + " params classes");
  }

  @Test
  void everyParamsBuilderCopiesListsAndByteArrays() throws Exception {
    int lists = 0;
    for (Method method : methodsByOperation().values()) {
      if (method.getParameterCount() != 2) {
        continue;
      }
      Class<?> paramsType = method.getParameterTypes()[0];
      for (Method setter : ParamsProbe.setters(paramsType)) {
        Class<?> type = setter.getParameterTypes()[0];
        if (type == List.class) {
          List<Object> source = new ArrayList<>(List.of("a"));
          Object built = ParamsProbe.build(paramsType, Map.of(setter, source));
          source.add("changed later");
          List<?> kept = (List<?>) ParamsProbe.accessor(paramsType, setter).invoke(built);
          assertEquals(List.of("a"), kept, setter.toString());
          try {
            kept.clear();
            fail(setter + " hands out a modifiable list");
          } catch (UnsupportedOperationException expected) {
            // the list the params hold cannot be changed through the accessor either
          }
          lists++;
        } else if (type == byte[].class) {
          byte[] source = {1, 2, 3};
          Object built = ParamsProbe.build(paramsType, Map.of(setter, source));
          source[0] = 9;
          byte[] kept = (byte[]) ParamsProbe.accessor(paramsType, setter).invoke(built);
          assertEquals(1, kept[0], setter + " keeps the caller's array");
          kept[1] = 9;
          byte[] again = (byte[]) ParamsProbe.accessor(paramsType, setter).invoke(built);
          assertEquals(2, again[1], setter + " hands out its own array");
        }
      }
    }
    assertTrue(lists >= 10, "only " + lists + " list fields were checked");
  }

  @Test
  void aBuilderCanBeReusedWithoutChangingWhatItBuilt() {
    var builder =
        io.github.baljinnyamday.xyp.meta.CheckAccessParams.builder()
            .operationNameList(List.of("WS100101_getCitizenIDCardInfo"))
            .type("ws");
    var first = builder.build();
    var second = builder.type("other").operationNameList(null).build();

    assertEquals("ws", first.type());
    assertEquals(List.of("WS100101_getCitizenIDCardInfo"), first.operationNameList());
    assertEquals("other", second.type());
    assertEquals(null, second.operationNameList());
    assertEquals(
        List.of("operationNameList", "clientId", "type"),
        first.toParams().entries().stream().map(Map.Entry::getKey).toList());
  }

  /**
   * The zeep-verified envelopes again, this time built by the generated methods from the generated
   * params classes: the field order the generator chose is the order the WSDL declares.
   */
  @TestFactory
  Stream<DynamicTest> theGeneratedMethodsSendTheZeepVerifiedEnvelopes() throws IOException {
    Map<String, Method> methods = methodsByOperation();
    List<JsonNode> covered =
        Fixtures.envelopes().stream()
            .filter(fixture -> methods.containsKey(fixture.get("operation").asText()))
            .toList();
    // The WSDLs hold 163 operations, and the public catalog lists 67 of them; the rest have no
    // typed method (they are reachable through call() with CallOptions endpoint).
    assertTrue(covered.size() >= 67, "only " + covered.size() + " fixtures have a typed method");
    return covered.stream()
        .map(
            fixture ->
                DynamicTest.dynamicTest(
                    fixture.get("operation").asText(),
                    () -> {
                      Method method = methods.get(fixture.get("operation").asText());
                      RequestParams params =
                          method.getParameterCount() == 2
                              ? fixtureParams(method.getParameterTypes()[0], fixture)
                              : null;
                      Object group = groupOf(method);
                      int before = fake.recorded().size();
                      method.invoke(group, arguments(method, params, Fixtures.options(fixture)));
                      FakeXyp.Recorded post =
                          lastPost(fake.recorded().subList(before, fake.recorded().size()));
                      assertEquals(fixture.get("envelope").asText(), post.body());
                    }));
  }

  private static Object groupOf(Method method) throws ReflectiveOperationException {
    for (Method accessor : groupAccessors()) {
      if (accessor.getReturnType() == method.getDeclaringClass()) {
        return accessor.invoke(client);
      }
    }
    throw new AssertionError("no accessor returns " + method.getDeclaringClass());
  }

  /** Fills the generated params class with the fixture's values, converted to each field's type. */
  private static RequestParams fixtureParams(Class<?> paramsType, JsonNode fixture)
      throws ReflectiveOperationException {
    Map<String, String> wireNames = ParamsProbe.wireNamesBySetter(paramsType);
    Map<String, Method> settersByWireName = new LinkedHashMap<>();
    for (Method setter : ParamsProbe.setters(paramsType)) {
      settersByWireName.put(wireNames.get(setter.getName()), setter);
    }
    Set<String> dateFields = new TreeSet<>();
    fixture.get("dateFields").forEach(name -> dateFields.add(name.asText()));
    Map<Method, Object> values = new LinkedHashMap<>();
    for (JsonNode nameNode : fixture.get("paramOrder")) {
      String name = nameNode.asText();
      Method setter = settersByWireName.get(name);
      assertNotNull(setter, paramsType.getSimpleName() + " has no field for " + name);
      values.put(
          setter,
          convert(
              fixture.get("params").get(name),
              setter.getGenericParameterTypes()[0],
              dateFields.contains(name)));
    }
    return ParamsProbe.build(paramsType, values);
  }

  private static Object convert(JsonNode value, Type type, boolean date) {
    if (type instanceof ParameterizedType list && list.getRawType() == List.class) {
      return List.of(convert(value, list.getActualTypeArguments()[0], date));
    }
    if (type == String.class) {
      return value.asText();
    }
    if (type == Long.class) {
      return value.isIntegralNumber() ? value.longValue() : Long.valueOf(value.asText());
    }
    if (type == Double.class) {
      return value.isNumber() ? value.doubleValue() : Double.valueOf(value.asText());
    }
    if (type == Boolean.class) {
      return value.booleanValue();
    }
    if (type == BigDecimal.class) {
      return new BigDecimal(value.asText());
    }
    if (type == XypDate.class) {
      return date
          ? XypDate.of(OffsetDateTime.parse(value.asText()))
          : XypDate.ofRaw(value.asText());
    }
    if (type == Object.class) {
      if (date) {
        return OffsetDateTime.parse(value.asText());
      }
      return value.isBoolean()
          ? (Object) value.booleanValue()
          : value.isIntegralNumber() ? (Object) value.longValue() : value.asText();
    }
    throw new AssertionError("no conversion of a fixture value to " + type);
  }

  /**
   * Java's names for a leading upper-case run: {@code IDCardInfoEm} is {@code idCardInfoEm}, like
   * the generator's rule.
   */
  private static String decapitalize(String name) {
    int run = 0;
    while (run < name.length() && Character.isUpperCase(name.charAt(run))) {
      run++;
    }
    if (run > 1 && run < name.length() && Character.isLowerCase(name.charAt(run))) {
      run--;
    }
    if (run == 0) {
      return name;
    }
    return name.substring(0, run).toLowerCase(Locale.ROOT) + name.substring(run);
  }

  /** Fills generated params classes by reflection. */
  static final class ParamsProbe {

    private static final Map<Class<?>, Map<String, String>> WIRE_NAMES = new LinkedHashMap<>();

    private ParamsProbe() {}

    static List<Method> setters(Class<?> paramsType) throws ReflectiveOperationException {
      Class<?> builder = paramsType.getMethod("builder").getReturnType();
      return Arrays.stream(builder.getDeclaredMethods())
          .filter(method -> Modifier.isPublic(method.getModifiers()))
          .filter(method -> method.getParameterCount() == 1 && method.getReturnType() == builder)
          .sorted(Comparator.comparing(Method::getName))
          .toList();
    }

    static Method accessor(Class<?> paramsType, Method setter) throws NoSuchMethodException {
      return paramsType.getMethod(setter.getName());
    }

    static RequestParams build(Class<?> paramsType, Map<Method, Object> values)
        throws ReflectiveOperationException {
      Object builder = paramsType.getMethod("builder").invoke(null);
      for (Map.Entry<Method, Object> value : values.entrySet()) {
        value.getKey().invoke(builder, value.getValue());
      }
      return (RequestParams) builder.getClass().getMethod("build").invoke(builder);
    }

    /**
     * Java field name to wire name, found by setting one field at a time and seeing which entry of
     * {@code toParams()} is no longer {@code null}.
     */
    static synchronized Map<String, String> wireNamesBySetter(Class<?> paramsType)
        throws ReflectiveOperationException {
      Map<String, String> cached = WIRE_NAMES.get(paramsType);
      if (cached != null) {
        return cached;
      }
      Map<String, String> names = new LinkedHashMap<>();
      for (Method setter : setters(paramsType)) {
        Params sent = build(paramsType, Map.of(setter, sample(setter))).toParams();
        List<String> set =
            sent.entries().stream()
                .filter(entry -> entry.getValue() != null)
                .map(Map.Entry::getKey)
                .toList();
        assertEquals(1, set.size(), setter + " sets " + set);
        names.put(setter.getName(), set.get(0));
      }
      WIRE_NAMES.put(paramsType, names);
      return names;
    }

    private static Object sample(Method setter) {
      Class<?> type = setter.getParameterTypes()[0];
      if (type == String.class || type == Object.class) {
        return "x";
      }
      if (type == Long.class) {
        return 1L;
      }
      if (type == Double.class) {
        return 1.5;
      }
      if (type == Boolean.class) {
        return Boolean.TRUE;
      }
      if (type == java.math.BigDecimal.class) {
        return java.math.BigDecimal.ONE;
      }
      if (type == XypDate.class) {
        return XypDate.ofRaw("2024-01-31");
      }
      if (type == List.class) {
        return List.of("x");
      }
      if (type == Map.class) {
        return Map.of("x", "y");
      }
      if (type == byte[].class) {
        return new byte[] {1};
      }
      throw new AssertionError(setter + " takes an unexpected " + type.getName());
    }
  }
}
