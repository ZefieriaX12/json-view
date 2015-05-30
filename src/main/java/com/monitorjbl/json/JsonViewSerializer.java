package com.monitorjbl.json;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class JsonViewSerializer extends JsonSerializer<JsonView> {
  private final int cacheSize;

  public JsonViewSerializer() {
    this(1000);
  }

  public JsonViewSerializer(int cacheSize) {
    this.cacheSize = cacheSize;
  }

  @Override
  public void serialize(JsonView result, JsonGenerator jgen, SerializerProvider serializers) throws IOException {
    new JsonWriter(jgen, result, cacheSize).write(null, result.getValue());
  }

  static class JsonWriter {
    //caches the results of the @JsonIgnore test to cut down on expensive reflection calls
    static final Map<Field, Boolean> hasJsonIgnoreCache = new ConcurrentHashMap<>();

    Stack<String> path = new Stack<>();
    String currentPath = "";
    Match currentMatch = null;

    final JsonGenerator jgen;
    final JsonView result;
    final int cacheSize;

    JsonWriter(JsonGenerator jgen, JsonView result, int cacheSize) {
      this.jgen = jgen;
      this.result = result;
      this.cacheSize = cacheSize;
    }

    boolean writePrimitive(Object obj) throws IOException {
      if (obj instanceof String) {
        jgen.writeString((String) obj);
      } else if (obj instanceof Integer) {
        jgen.writeNumber((Integer) obj);
      } else if (obj instanceof Long) {
        jgen.writeNumber((Long) obj);
      } else if (obj instanceof Double) {
        jgen.writeNumber((Double) obj);
      } else if (obj instanceof Float) {
        jgen.writeNumber((Float) obj);
      } else if (obj instanceof Boolean) {
        jgen.writeBoolean((Boolean) obj);
      } else {
        return false;
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    boolean writeList(Object obj) throws IOException {
      if (obj instanceof List || obj instanceof Set || obj.getClass().isArray()) {
        Iterable<Object> iter;
        if (obj.getClass().isArray()) {
          iter = Arrays.asList((Object[]) obj);
        } else {
          iter = (Iterable<Object>) obj;
        }

        jgen.writeStartArray();
        for (Object o : iter) {
          new JsonWriter(jgen, result, cacheSize).write(null, o);
        }
        jgen.writeEndArray();
      } else {
        return false;
      }
      return true;
    }

    @SuppressWarnings("unchecked")
    boolean writeMap(Object obj) throws IOException {
      if (obj instanceof Map) {
        Map<Object, Object> map = (Map<Object, Object>) obj;

        jgen.writeStartObject();
        for (Object key : map.keySet()) {
          jgen.writeFieldName(key.toString());
          new JsonWriter(jgen, result, cacheSize).write(null, map.get(key));
        }
        jgen.writeEndObject();
      } else {
        return false;
      }
      return true;
    }

    void writeObject(Object obj) throws IOException {
      jgen.writeStartObject();

      Class cls = obj.getClass();
      while (!cls.equals(Object.class)) {
        Field[] fields = cls.getDeclaredFields();
        for (Field field : fields) {
          try {
            field.setAccessible(true);
            Object val = field.get(obj);

            if (val != null && fieldAllowed(field, obj.getClass())) {
              String name = field.getName();
              jgen.writeFieldName(name);
              write(name, val);
            }
          } catch (IllegalArgumentException | IllegalAccessException e) {
            e.printStackTrace();
          }
        }
        cls = cls.getSuperclass();
      }

      jgen.writeEndObject();
    }

    boolean fieldAllowed(Field field, Class declaringClass) {
      String name = field.getName();
      String prefix = currentPath.length() > 0 ? currentPath + "." : "";

      //search for matcher
      Match match = null;
      Class cls = declaringClass;
      while (!cls.equals(Object.class) && match == null) {
        match = result.getMatch(cls);
        cls = cls.getSuperclass();
      }
      if (match == null) {
        match = currentMatch;
      }

      //if there is a match, respect it
      if (match != null) {
        currentMatch = match;
        return (containsMatchingPattern(match.getIncludes(), prefix + name) ||
            !annotatedWithIgnore(field)) && !containsMatchingPattern(match.getExcludes(), prefix + name);
      } else {
        //else, respect JsonIgnore only
        return !annotatedWithIgnore(field);
      }
    }

    boolean annotatedWithIgnore(Field f) {
      if (!hasJsonIgnoreCache.containsKey(f)) {
        JsonIgnore jsonIgnore = f.getAnnotation(JsonIgnore.class);
        JsonIgnoreProperties ignoreProperties = f.getDeclaringClass().getAnnotation(JsonIgnoreProperties.class);
        if (hasJsonIgnoreCache.size() > cacheSize) {
          hasJsonIgnoreCache.remove(hasJsonIgnoreCache.keySet().iterator().next());
        }
        hasJsonIgnoreCache.put(f, (jsonIgnore != null && jsonIgnore.value()) ||
            (ignoreProperties != null && Arrays.asList(ignoreProperties.value()).contains(f.getName())));
      }
      return hasJsonIgnoreCache.get(f);
    }

    boolean containsMatchingPattern(List<String> values, String pattern) {
      for (String val : values) {
        val = val.replaceAll("\\.", "\\\\.").replaceAll("\\*", ".*");
        if (Pattern.compile(val).matcher(pattern).matches()) {
          return true;
        }
      }
      return false;
    }

    void write(String fieldName, Object value) throws IOException {
      if (fieldName != null) {
        path.push(fieldName);
        updateCurrentPath();
      }

      //try to handle all primitives before treating this as json object
      if (value != null && !writePrimitive(value) && !writeList(value) && !writeMap(value)) {
        writeObject(value);
      }

      if (fieldName != null) {
        path.pop();
        updateCurrentPath();
      }
    }

    void updateCurrentPath() {
      StringBuilder builder = new StringBuilder();
      for (String s : path) {
        builder.append(".");
        builder.append(s);
      }
      currentPath = builder.length() > 0 ? builder.toString().substring(1) : "";
    }
  }
}