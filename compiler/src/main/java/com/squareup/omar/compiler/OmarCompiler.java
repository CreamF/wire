// Copyright 2013 Square, Inc.
package com.squareup.omar.compiler;

import com.squareup.omar.ProtoEnum;
import com.squareup.omar.ProtoField;
import com.squareup.javawriter.JavaWriter;
import com.squareup.protoparser.EnumType;
import com.squareup.protoparser.ExtendDeclaration;
import com.squareup.protoparser.MessageType;
import com.squareup.protoparser.ProtoFile;
import com.squareup.protoparser.ProtoSchemaParser;
import com.squareup.protoparser.Type;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

import static com.squareup.protoparser.MessageType.Field;

public class OmarCompiler {

  private static final Map<String, String> javaTypes = new HashMap<String, String>();
  private static final Map<String, String> protoFieldTypes = new HashMap<String, String>();
  private static final Set<String> packableTypes = new HashSet<String>();
  private static final Set<String> javaKeywords = new HashSet<String>(Arrays.asList(
      "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
      "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
      "final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof",
      "int", "interface", "long", "native", "new", "package", "private", "protected", "public",
      "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
      "throw", "throws", "transient", "try", "void", "volatile", "while"));

  static {
    javaTypes.put("bool", "Boolean");
    javaTypes.put("bytes", "byte[]");
    javaTypes.put("double", "Double");
    javaTypes.put("float", "Float");
    javaTypes.put("fixed32", "Integer");
    javaTypes.put("fixed64", "Long");
    javaTypes.put("int32", "Integer");
    javaTypes.put("int64", "Long");
    javaTypes.put("sfixed32", "Integer");
    javaTypes.put("sfixed64", "Long");
    javaTypes.put("sint32", "Integer");
    javaTypes.put("sint64", "Long");
    javaTypes.put("string", "String");
    javaTypes.put("uint32", "Integer");
    javaTypes.put("uint64", "Long");

    protoFieldTypes.put("bool", "Omar.BOOL");
    protoFieldTypes.put("bytes", "Omar.BYTES");
    protoFieldTypes.put("double", "Omar.DOUBLE");
    protoFieldTypes.put("float", "Omar.FLOAT");
    protoFieldTypes.put("fixed32", "Omar.FIXED32");
    protoFieldTypes.put("fixed64", "Omar.FIXED64");
    protoFieldTypes.put("int32", "Omar.INT32");
    protoFieldTypes.put("int64", "Omar.INT64");
    protoFieldTypes.put("sfixed32", "Omar.SFIXED32");
    protoFieldTypes.put("sfixed64", "Omar.SFIXED64");
    protoFieldTypes.put("sint32", "Omar.SINT32");
    protoFieldTypes.put("sint64", "Omar.SINT64");
    protoFieldTypes.put("string", "Omar.STRING");
    protoFieldTypes.put("uint32", "Omar.UINT32");
    protoFieldTypes.put("uint64", "Omar.UINT64");

    packableTypes.add("bool");
    packableTypes.add("double");
    packableTypes.add("float");
    packableTypes.add("fixed32");
    packableTypes.add("fixed64");
    packableTypes.add("int32");
    packableTypes.add("int64");
    packableTypes.add("sfixed32");
    packableTypes.add("sfixed64");
    packableTypes.add("sint32");
    packableTypes.add("sint64");
    packableTypes.add("uint32");
    packableTypes.add("uint64");
  }

  private final String repoPath;
  private final ProtoFile protoFile;
  private final Set<String> loadedDependencies = new HashSet<String>();
  private final Map<String, String> javaSymbolMap = new HashMap<String, String>();
  private final Set<String> enumTypes = new HashSet<String>();
  private final Map<String, String> enumDefaults = new HashMap<String, String>();

  private static final String PROTO_PATH_FLAG = "--proto_path=";
  private static final String JAVA_OUT_FLAG = "--java_out=";
  private static final String FILES_FLAG = "--files=";

  // usage:
  // java OmarCompiler --proto_path=<path> --java_out=<path> [--files=<protos.include>]
  //     [file [file...]]
  public static void main(String[] args) throws Exception {
    String protoPath = null;
    String javaOut = null;
    List<String> sourceFilenames = new ArrayList<String>();

    int index = 0;
    while (index < args.length) {
      if (args[index].startsWith(PROTO_PATH_FLAG)) {
        protoPath = args[index].substring(PROTO_PATH_FLAG.length());
      } else if (args[index].startsWith(JAVA_OUT_FLAG)) {
        javaOut = args[index].substring(JAVA_OUT_FLAG.length());
      } else if (args[index].startsWith(FILES_FLAG)) {
        File files = new File(args[index].substring(FILES_FLAG.length()));
        String[] filenames = new Scanner(files).useDelimiter("\\A").next().split("\n");
        sourceFilenames.addAll(Arrays.asList(filenames));
      } else {
        sourceFilenames.add(args[index]);
      }
      index++;
    }
    if (protoPath == null) {
      System.err.println("Must specify " + PROTO_PATH_FLAG + " flag");
      System.exit(1);
    }
    if (javaOut == null) {
      System.err.println("Must specify " + JAVA_OUT_FLAG + " flag");
      System.exit(1);
    }
    for (String sourceFilename : sourceFilenames) {
      OmarCompiler omarCompiler = new OmarCompiler(protoPath, sourceFilename);
      omarCompiler.compile(omarCompiler.getJavaWriter(javaOut));
    }
  }

  public OmarCompiler(String protoPath, String sourceFilename) throws IOException {
    this.repoPath = protoPath;
    String filename = protoPath + "/" + sourceFilename;
    System.out.println("Reading proto source file " + filename);
    this.protoFile = ProtoSchemaParser.parse(new File(protoPath + "/" + sourceFilename));
  }

  public JavaWriter getJavaWriter(String javaOut) throws IOException {
    String javaPackage = protoFile.getJavaPackage();
    String directory = javaOut + "/" + javaPackage.replace(".", "/");
    new File(directory).mkdirs();

    String className = outerClassName(protoFile);
    String fileName = directory + "/" + className + ".java";
    System.out.println("Writing generated code to " + fileName);
    return new JavaWriter(new FileWriter(fileName));
  }

  private String typeBeingGenerated = "";
  private JavaWriter writer;

  public void compile(JavaWriter writer) throws IOException {
    this.writer = writer;
    loadSymbols(protoFile);

    String className = outerClassName(protoFile);
    try {
      writer.emitJavadoc("Code generated by \"Omar\" little protobuf compiler, do not edit."
          + "\nSource file: %s", protoFile.getFileName());
      writer.emitPackage(protoFile.getJavaPackage());

      Set<String> imports = new LinkedHashSet<String>();
      List<Type> types = protoFile.getTypes();
      boolean hasMessage = hasMessage(types);
      boolean hasExtensions = hasExtensions(protoFile.getTypes());
      boolean hasExtends = hasExtends();

      if (hasMessage) {
        imports.add("com.squareup.omar.Message");
        imports.add("com.squareup.omar.Omar");
      }
      if (hasEnum(types)) {
        imports.add("com.squareup.omar.ProtoEnum");
      }
      if (hasMessage) {
        imports.add("com.squareup.omar.ProtoField");
        imports.add("com.squareup.omar.UninitializedMessageException");
      }
      if (hasRepeatedField(types)) {
        imports.add("java.util.List");
      }
      if (hasExtensions) {
        imports.add("java.util.Collections");
        imports.add("java.util.Map");
        imports.add("java.util.TreeMap");
      }
      if (hasExtends) {
        imports.add("com.squareup.omar.Omar");
      }
      writer.emitImports(imports);

      if (hasExtends) {
        writer.emitEmptyLine();
        writer.emitStaticImports("com.squareup.omar.Message.ExtendableMessage.Extension");
      }
      writer.emitEmptyLine();
      writer.beginType(className, "class", Modifier.PUBLIC | Modifier.FINAL);
      typeBeingGenerated = className + ".";

      // Private constructor
      writer.emitEmptyLine();
      writer.beginMethod(null, className, Modifier.PRIVATE);
      writer.endMethod();

      if (hasExtends) {
        writer.emitEmptyLine();
        emitExtensions(writer);
      }

      for (Type type : types) {
        String savedType = typeBeingGenerated;
        typeBeingGenerated += type.getName() + ".";
        emitType(type, protoFile.getPackageName() + ".");
        typeBeingGenerated = savedType;
      }
      writer.endType();
    } finally {
      writer.close();
    }
  }

  private boolean hasExtends() {
    return !protoFile.getExtendDeclarations().isEmpty();
  }

  private void emitExtensions(JavaWriter writer) throws IOException {
    for (ExtendDeclaration extend : protoFile.getExtendDeclarations()) {
      String fullyQualifiedName = extend.getFullyQualifiedName();
      String javaName = javaName(null, fullyQualifiedName);
      String name = shortenJavaName(javaName);
      for (MessageType.Field field : extend.getFields()) {
        String fieldType = field.getType();
        String type = javaName(null, fieldType);
        boolean isEnum = isEnum(fieldType);
        if (type == null) {
          String qualifiedFieldType = protoFile.getPackageName() + "." + fieldType;
          isEnum = isEnum(qualifiedFieldType);
          type = javaName(null, qualifiedFieldType);
        }
        type = shortenJavaName(type);
        String initialValue;
        if (isScalar(field)) {
          if (isRepeated(field)) {
            initialValue = String.format("Extension.getRepeatedExtension(%s.class, %s, %s, %s)",
                name, field.getTag(), protoFieldType(field.getType()), isPacked(field, false));
          } else {
            initialValue = String.format("Extension.getExtension(%s.class, %s, %s, Omar.%s)",
                name, field.getTag(), protoFieldType(field.getType()), field.getLabel());
          }
        } else if (isEnum) {
          if (isRepeated(field)) {
            initialValue =
                String.format("Extension.getRepeatedEnumExtension(%s.class, %s, %s, %s.class)",
                    name, field.getTag(), isPacked(field, true), type);
          } else {
            initialValue =
                String.format("Extension.getEnumExtension(%s.class, %s, Omar.%s, %s.class)",
                    name, field.getTag(), field.getLabel(), type);
          }
        } else {
          if (isRepeated(field)) {
            initialValue =
                String.format("Extension.getRepeatedMessageExtension(%s.class, %s, %s.class)",
                    name, field.getTag(), type);
          } else {
            initialValue =
                String.format("Extension.getMessageExtension(%s.class, %s, Omar.%s, %s.class)",
                    name, field.getTag(), field.getLabel(), type);
          }
        }
        if (isRepeated(field)) {
          type = "List<" + type + ">";
        }
        writer.emitField("Extension<" + name + ", " + type + ">", field.getName(),
            Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, initialValue);
      }
    }
  }

  private boolean hasExtensions(MessageType messageType) {
    return !messageType.getExtensions().isEmpty();
  }

  private void emitType(Type type, String currentType) throws IOException {
    writer.emitEmptyLine();
    if (type instanceof MessageType) {
      MessageType messageType = (MessageType) type;
      boolean hasExtensions = hasExtensions(messageType);

      String name = type.getName();
      writer.beginType(name, "class",
          Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, null,
          hasExtensions ? "Message.ExtendableMessage<" + name + ">" : "Message");
      if (hasExtensions) {
        writer.emitEmptyLine();
        writer.emitField("Map<Extension<" + name + ", ?>, Object>",
            "extensionMap", Modifier.PUBLIC | Modifier.FINAL);
      }
      emitMessageDefaults(messageType);
      emitMessageFields(messageType);
      emitMessageConstructor(messageType);
      if (hasExtensions) {
        emitMessageGetExtension(messageType);
      }
      emitMessageEquals(messageType);
      emitMessageHashCode(messageType);
      emitMessageToString(messageType);
      emitBuilder(messageType);

      for (Type nestedType : type.getNestedTypes()) {
        emitType(nestedType, currentType + nestedType.getName() + ".");
      }

      writer.endType();
    } else if (type instanceof EnumType) {
      EnumType enumType = (EnumType) type;
      writer.beginType(enumType.getName(), "enum", Modifier.PUBLIC);
      for (EnumType.Value value : enumType.getValues()) {
        writer.emitAnnotation(ProtoEnum.class, value.getTag());
        writer.emitEnumValue(value.getName());
      }
      writer.endType();
    }
  }

  // Example:
  //
  // public static final Integer optional_int32_default = 123;
  //
  private void emitMessageDefaults(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    for (Field field : messageType.getFields()) {
      String javaName = javaName(messageType, field.getType());
      if (isRepeated(field)) {
        javaName = "List<" + javaName + ">";
      }
      String defaultValue = getDefaultValue(messageType, field);
      writer.emitField(javaName, sanitize(field.getName()) + "_default",
          Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, defaultValue);
    }
  }

  // Example:
  //
  // /**
  //  * An optional int32
  //  */
  // @ProtoField(
  //   tag = 1,
  //   type = Omar.INT32
  // )
  // public final Integer optional_int32;
  //
  private void emitMessageFields(MessageType messageType)
      throws IOException {
    for (Field field : messageType.getFields()) {
      String javaName = javaName(messageType, field.getType());
      Map<String, String> map = new LinkedHashMap<String, String>();
      map.put("tag", String.valueOf(field.getTag()));

      String type = field.getType();
      boolean isEnum = false;
      if (isScalar(field)) {
        map.put("type", protoFieldType(type));
      } else {
        String fullyQualifiedName = fullyQualifiedName(messageType, type);
        isEnum = isEnum(fullyQualifiedName);
        if (isEnum) {
          map.put("type", "Omar.ENUM");
        }
      }

      if (isPacked(field, isEnum)) {
        map.put("packed", "true");
      }

      if (!isOptional(field)) {
        map.put("label", "Omar." + field.getLabel().toString());
      }

      writer.emitEmptyLine();
      String documentation = field.getDocumentation();
      if (!isBlank(documentation)) {
        writer.emitJavadoc(documentation.replace("%", "%%"));
      }
      writer.emitAnnotation(ProtoField.class, map);

      if (isRepeated(field)) {
        javaName = "List<" + javaName + ">";
      }
      writer.emitField(javaName, sanitize(field.getName()), Modifier.PUBLIC | Modifier.FINAL);
    }
  }

  private String sanitize(String name) {
    if (javaKeywords.contains(name)) {
      return "_" + name;
    }
    return name;
  }

  // Example:
  //
  // private SimpleMessage(Builder builder) {
  //   this.optional_int32 = builder.optional_int32;
  // }
  //
  private void emitMessageConstructor(MessageType messageType) throws IOException {
    writer.emitEmptyLine();
    writer.beginMethod(null, messageType.getName(), Modifier.PRIVATE, "Builder", "builder");
    for (Field field : messageType.getFields()) {
      if (isRepeated(field)) {
        writer.emitStatement("this.%1$s = Omar.unmodifiableCopyOf(builder.%1$s)",
            sanitize(field.getName()));
      } else {
        writer.emitStatement("this.%1$s = builder.%1$s", sanitize(field.getName()));
      }
    }
    if (hasExtensions(messageType)) {
      writer.emitStatement("this.extensionMap = Collections.unmodifiableMap(new TreeMap<Extension<"
          + messageType.getName() + ", ?>, Object>(builder.extensionMap))");
    }
    writer.endMethod();
  }

  // Example:
  //
  // @Override public <Type> Type getExtension(Extension<ExternalMessage, Type> extension) {
  //   return (Type) extensionMap.get(extension);
  // }
  //
  private void emitMessageGetExtension(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("<Type> Type", "getExtension", Modifier.PUBLIC,
        "Extension<" + messageType.getName() + ", Type>", "extension");
    writer.emitStatement("return (Type) extensionMap.get(extension)");
    writer.endMethod();
  }

  // Example:
  //
  // @Override
  // public boolean equals(Object other) {
  //   if (!(other instanceof SimpleMessage)) return false;
  //   SimpleMessage o = (SimpleMessage) other;
  //   if (!Omar.equals(optional_int32, o.optional_int32)) return false;
  //   return true;
  //
  private void emitMessageEquals(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("boolean", "equals", Modifier.PUBLIC, "Object", "other");
    writer.emitStatement("if (!(other instanceof %s)) return false", messageType.getName());
    writer.emitStatement("%1$s o = (%1$s) other", messageType.getName());
    if (hasExtensions(messageType)) {
      writer.emitStatement("if (!extensionMap.equals(o.extensionMap)) return false");
    }
    for (Field field : messageType.getFields()) {
      writer.emitStatement("if (!Omar.equals(%1$s, o.%1$s)) return false",
          sanitize(field.getName()));
    }
    writer.emitStatement("return true");
    writer.endMethod();
  }

  // Example:
  //
  // @Override
  // public int hashCode() {
  //   int hashCode = extensionMap.hashCode();
  //   hashCode = hashCode * 37 + (f != null ? f.hashCode() : 0);
  //   return hashCode;
  // }
  //
  private void emitMessageHashCode(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("int", "hashCode", Modifier.PUBLIC);

    boolean hasExtensions = !hasExtensions(messageType);
    if (hasExtensions && messageType.getFields().size() == 1) {
      writer.emitStatement("return %1$s != null ? %1$s.hashCode() : 0",
          messageType.getFields().get(0).getName());
    } else {
      if (hasExtensions(messageType)) {
        writer.emitStatement("int hashCode = extensionMap.hashCode()");
      } else {
        writer.emitStatement("int hashCode = 0");
      }
      for (Field field : messageType.getFields()) {
        writer.emitStatement("hashCode = hashCode * 37 + (%1$s != null ? %1$s.hashCode() : 0)",
          sanitize(field.getName()));
      }
      writer.emitStatement("return hashCode");
    }
    writer.endMethod();
  }

  // Example:
  //
  // @Override
  // public String toString() {
  //   return String.format("SimpleMessage{" +
  //     "optional_int32=%s}",
  //     optional_int32);
  // }
  //
  private void emitMessageToString(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("String", "toString", Modifier.PUBLIC);

    if (messageType.getFields().isEmpty()) {
      writer.emitStatement(String.format("return \"%s{}\"", messageType.getName()));
    } else {
      StringBuilder format = new StringBuilder();
      StringBuilder args = new StringBuilder();
      String formatSep = "\"";
      String argsSep = "";
      for (Field field : messageType.getFields()) {
        String sanitized = sanitize(field.getName());
        format.append(String.format("%s%s=%%s", formatSep, sanitized));
        if (field.getType().equals("bytes")) {
          args.append(String.format("%s%s", argsSep, "Omar.toString(" + sanitized + ")"));
        } else {
          args.append(String.format("%s%s", argsSep, sanitized));
        }
        formatSep = ",\" +\n\"";
        argsSep = ",\n";
      }
      if (hasExtensions(messageType)) {
        format.append(String.format("%s{extensionMap=%%s", formatSep));
        args.append(String.format("%sOmar.toString(extensionMap)", argsSep));
      }

      writer.emitStatement("return String.format(\"%s{\" +\n%s}\",\n%s)", messageType.getName(),
          format.toString(), args.toString());
    }
    writer.endMethod();
  }

  private void emitBuilder(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.beginType("Builder", "class", Modifier.PUBLIC | Modifier.STATIC | Modifier.FINAL, null,
        (hasExtensions(messageType) ? "ExtendableMessage.ExtendableBuilder<" : "Message.Builder<")
            + messageType.getName() + ">");
    emitBuilderFields(messageType);
    emitBuilderConstructors(messageType);
    emitBuilderSetters(messageType);
    if (hasExtensions(messageType)) {
      emitBuilderGetExtension(messageType);
      emitBuilderSetExtension(messageType);
    }
    emitBuilderIsInitialized(messageType);
    emitBuilderBuild(messageType);
    writer.endType();
  }

  private void emitBuilderFields(MessageType messageType) throws IOException {
    writer.emitEmptyLine();

    String name = messageType.getName();
    if (hasExtensions(messageType)) {
      writer.emitField("Map<Extension<" + name + ", ?>, Object>",
          "extensionMap", Modifier.PRIVATE | Modifier.FINAL,
          "new TreeMap<Extension<" + name + ", ?>, Object>()");
      writer.emitEmptyLine();
    }
    for (Field field : messageType.getFields()) {
      String javaName = javaName(messageType, field.getType());
      if (isRepeated(field)) {
        javaName = "List<" + javaName + ">";
      }
      writer.emitField(javaName, sanitize(field.getName()), Modifier.PUBLIC);
    }
  }

  private void emitBuilderConstructors(MessageType messageType) throws IOException {
    writer.emitEmptyLine();
    writer.beginMethod(null, "Builder", Modifier.PUBLIC);
    writer.endMethod();

    writer.emitEmptyLine();
    writer.beginMethod(null, "Builder", Modifier.PUBLIC, messageType.getName(), "message");
    for (Field field : messageType.getFields()) {
      if (isRepeated(field)) {
        writer.emitStatement("this.%1$s = Omar.copyOf(message.%1$s)", sanitize(field.getName()));
      } else {
        writer.emitStatement("this.%1$s = message.%1$s", sanitize(field.getName()));
      }
    }
    if (hasExtensions(messageType)) {
      writer.emitStatement("this.extensionMap.putAll(message.extensionMap)");
    }
    writer.endMethod();
  }

  private String getDefaultValue(MessageType messageType, Field field) {
    String initialValue = field.getDefault();
    // Qualify message and enum values
    boolean isRepeated = field.getLabel() == MessageType.Label.REPEATED;
    if (isRepeated) {
      return "java.util.Collections.emptyList()";
    }
    String javaName = javaName(messageType, field.getType());
    if (!isScalar(field)) {
      if (initialValue != null) {
        return javaName + "." + initialValue;
      } else {
        String fullyQualifiedName = fullyQualifiedName(messageType, field.getType());
        if (isEnum(fullyQualifiedName)) {
          return field.getType() + "." + enumDefaults.get(fullyQualifiedName);
        } else {
          return "Omar.getDefaultInstance(" + javaName + ".class)";
        }
      }
    }
    if (javaName.equals("Boolean")) {
      return initialValue == null ? "false" : initialValue;
    } else if (javaName.equals("Double")) {
      return initialValue == null ? "0D" : initialValue + "D";
    } else if (javaName.equals("Integer")) {
      return initialValue == null ? "0" : initialValue;
    } else if (javaName.equals("Long")) {
      // Add an 'L' to Long values
      return initialValue == null ? "0L" : initialValue + "L";
    } else  if (javaName.equals("Float")) {
      // Add an 'F' to Float values
      return initialValue == null ? "0F" : initialValue + "F";
    } else if (javaName.equals("String")) {
      // Quote String values
      return "\"" + (initialValue == null
          ? "" : initialValue.replaceAll("[\\\\]", "\\\\").replaceAll("[\"]", "\\\"")) + "\"";
    } else {
      return "null";
    }
  }

  private void emitBuilderSetters(MessageType messageType) throws IOException {
    for (Field field : messageType.getFields()) {
      String javaName = javaName(messageType, field.getType());
      if (isRepeated(field)) {
        javaName = "List<" + javaName + ">";
      }
      List<String> args = new ArrayList<String>();
      args.add(javaName);
      String sanitized = sanitize(field.getName());
      args.add(sanitized);

      writer.emitEmptyLine();
      writer.beginMethod("Builder", sanitized, Modifier.PUBLIC, args, null);
      writer.emitStatement("this.%1$s = %1$s", sanitized);
      writer.emitStatement("return this");
      writer.endMethod();
    }
  }

  private void emitBuilderGetExtension(MessageType messageType)
      throws IOException {
    emitMessageGetExtension(messageType);
  }

  // Example:
  //
  // @Override
  // public <Type> Builder setExtension(Extension<ExternalMessage, Type> extension, Type value) {
  //   extensionMap.put(extension, value);
  //   return this;
  // }
  //
  private void emitBuilderSetExtension(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("<Type> Builder", "setExtension", Modifier.PUBLIC,
        "Extension<" + messageType.getName() + ", Type>", "extension", "Type", "value");
    writer.emitStatement("extensionMap.put(extension, value)");
    writer.emitStatement("return this");
    writer.endMethod();
  }

  private void emitBuilderIsInitialized(MessageType messageType)
      throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod("boolean", "isInitialized", Modifier.PUBLIC);
    for (Field field : messageType.getFields()) {
      if (isRequired(field)) {
        writer.emitStatement("if (%s == null) return false", field.getName());
      }
    }
    writer.emitStatement("return true");
    writer.endMethod();
  }

  private void emitBuilderBuild(MessageType messageType) throws IOException {
    writer.emitEmptyLine();
    writer.emitAnnotation(Override.class);
    writer.beginMethod(messageType.getName(), "build", Modifier.PUBLIC);
    writer.emitStatement("if (!isInitialized()) throw new UninitializedMessageException()");
    writer.emitStatement("return new %s(this)", messageType.getName());
    writer.endMethod();
  }

  private static String camelCase(String s) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      if (i == 0 || s.charAt(i - 1) == '_') {
        sb.append(Character.toUpperCase(s.charAt(i)));
      } else if (s.charAt(i) != '_') {
        sb.append(s.charAt(i));
      }
    }
    return sb.toString();
  }

  private static String outerClassName(ProtoFile protoFile) {
    String className = (String) protoFile.getOptions().get("java_outer_classname");
    if (className == null) {
      String fileName = protoFile.getFileName();
      String protoFileName = fileName.substring(fileName.lastIndexOf("/") + 1);
      protoFileName = protoFileName.substring(0, protoFileName.indexOf("."));
      className = camelCase(protoFileName);
    }
    return className;
  }

  private void addTypes(List<Type> types, String javaPrefix) {
    for (Type type : types) {
      String name = type.getName();
      String fqName = type.getFullyQualifiedName();
      javaSymbolMap.put(fqName, javaPrefix + name);
      if (type instanceof EnumType) {
        enumTypes.add(fqName);
        enumDefaults.put(fqName, ((EnumType) type).getValues().get(0).getName());
      }
      addTypes(type.getNestedTypes(), javaPrefix + name + ".");
    }
  }

  private void loadSymbols(ProtoFile protoFile) throws IOException {
    // Load symbols from imports
    for (String dependency : protoFile.getDependencies()) {
      if (!loadedDependencies.contains(dependency)) {
        File dep = new File(repoPath + "/" + dependency);
        ProtoFile dependencyFile = ProtoSchemaParser.parse(dep);
        loadSymbols(dependencyFile);
        loadedDependencies.add(dependency);
      }
    }

    addTypes(protoFile.getTypes(),
        protoFile.getJavaPackage() + "." + outerClassName(protoFile) + ".");
  }

  private boolean hasEnum(List<Type> types) {
    for (Type type : types) {
      if (type instanceof EnumType) {
        return true;
      }
      if (hasEnum(type.getNestedTypes())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasExtensions(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType && hasExtensions(((MessageType) type))) {
        return true;
      }
      if (hasExtensions(type.getNestedTypes())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasMessage(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType) {
        return true;
      }
      if (hasMessage(type.getNestedTypes())) {
        return true;
      }
    }
    return false;
  }

  private boolean hasRepeatedField(List<Type> types) {
    for (Type type : types) {
      if (type instanceof MessageType) {
        for (Field field : ((MessageType) type).getFields()) {
          if (isRepeated(field)) {
            return true;
          }
        }
      }
      if (hasRepeatedField(type.getNestedTypes())) {
        return true;
      }
    }
    return false;
  }

  private boolean isBlank(String documentation) {
    return documentation == null || documentation.isEmpty();
  }

  private String protoFieldType(String type) {
    String protoFieldType = protoFieldTypes.get(type);
    return protoFieldType != null ? protoFieldType : type + ".class";
  }

  private boolean isScalar(Field field) {
    return protoFieldTypes.containsKey(field.getType());
  }

  private boolean isEnum(String fieldType) {
    return enumTypes.contains(fieldType);
  }

  private boolean isOptional(Field field) {
    return field.getLabel() == MessageType.Label.OPTIONAL;
  }

  private boolean isRepeated(Field field) {
    return field.getLabel() == MessageType.Label.REPEATED;
  }

  private boolean isPacked(Field field, boolean isEnum) {
    return "true".equals(field.getExtensions().get("packed"))
        && (packableTypes.contains(field.getType()) || isEnum);
  }

  private boolean isRequired(Field field) {
    return field.getLabel() == MessageType.Label.REQUIRED;
  }

  private String fullyQualifiedName(MessageType messageType, String type) {
    if (type.contains(".")) {
      return type;
    } else {
      String prefix = messageType.getFullyQualifiedName();
      while (prefix.contains(".")) {
        String fqname = prefix + "." + type;
        if (javaSymbolMap.containsKey(fqname)) {
          return fqname;
        }
        prefix = prefix.substring(0, prefix.lastIndexOf('.'));
      }
    }
    throw new RuntimeException("Unknown type " + type + " in message " + messageType.getName());
  }

  private String shortenJavaName(String fullyQualifiedName) {
    String javaPackage = protoFile.getJavaPackage() + ".";
    if (fullyQualifiedName.startsWith(javaPackage)) {
      fullyQualifiedName = fullyQualifiedName.substring(javaPackage.length());
    }
    if (fullyQualifiedName.startsWith(typeBeingGenerated)) {
      fullyQualifiedName = fullyQualifiedName.substring(typeBeingGenerated.length());
    }
    return fullyQualifiedName;
  }

  private String javaName(MessageType messageType, String type) {
    String scalarType = javaTypes.get(type);
    if (scalarType != null) {
      return scalarType;
    }

    // Assume names containing a '.' are already fully-qualified
    if (type.contains(".")) {
      return javaSymbolMap.get(type);
    } else {
      String prefix = messageType != null ? messageType.getFullyQualifiedName() : "";
      while (prefix.contains(".")) {
        String fqname = prefix + "." + type;
        String javaName = javaSymbolMap.get(fqname);
        if (javaName != null) {
          return shortenJavaName(javaName);
        }
        prefix = prefix.substring(0, prefix.lastIndexOf('.'));
      }
    }
    return null;
  }
}
