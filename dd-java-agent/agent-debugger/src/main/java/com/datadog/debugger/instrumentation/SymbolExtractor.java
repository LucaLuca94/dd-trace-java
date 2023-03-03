package com.datadog.debugger.instrumentation;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SymbolExtractor {

  private final ClassExtraction classExtraction;

  public SymbolExtractor(String classFilePath, byte[] classFileBuffer) {
    ClassNode classNode = parseClassFile(classFilePath, classFileBuffer);
    this.classExtraction = extractScopes(classNode);
//    extractBlockScopes(classNode.methods.get(1));
    extractScopesFromVariables(classNode.methods.get(1));
  }

  private void extractScopesFromVariables(MethodNode methodNode) {
    LineNavigator lineNavigator = new LineNavigator(methodNode.instructions);
    Map<Label, Integer> monotonicLineMap = getMonotonicLineMap(methodNode);
    List<Block> blocks = new ArrayList<>();
    Map<LabelNode, List<LocalVariableNode>> varsByEndLabel = new HashMap<>();
    for (LocalVariableNode localVariable : methodNode.localVariables) {
      varsByEndLabel.merge(localVariable.end, new ArrayList<>(Collections.singletonList(localVariable)), (curr, next) -> {
        curr.addAll(next);
        return curr;
      });
    }

    for (Map.Entry<LabelNode, List<LocalVariableNode>> entry : varsByEndLabel.entrySet()) {
      List<Variable> variables = new ArrayList<>();
      int minLine = Integer.MAX_VALUE;
      for (LocalVariableNode var : entry.getValue()) {
//        int line = lineNavigator.getNextLine(var.start);
        int line = monotonicLineMap.get(var.start.getLabel());
        minLine = Math.min(line, minLine);
        variables.add(new Variable(var.name, line));
      }
//      int endLine = lineNavigator.getPreviousLine(entry.getKey(), minLine);
      int endLine = monotonicLineMap.get(entry.getKey().getLabel());
      blocks.add(new Block(minLine, endLine, variables));
    }

    System.out.println(blocks);
  }

  static class LineNavigator {
    private final InsnList insns;

    LineNavigator(InsnList insns) {
      this.insns = insns;
      AbstractInsnNode node = insns.getFirst();
      while (node != null) {
        if (node.getType() == AbstractInsnNode.LINE) {
          LineNumberNode lineNumberNode = (LineNumberNode) node;
          System.out.println("line: " + lineNumberNode.line);
        }
        node = node.getNext();
      }
    }

    public int getNextLine(LabelNode label) {
      int index = insns.indexOf(label);
      AbstractInsnNode node = insns.get(index);
      while (node != null) {
        if (node.getType() == AbstractInsnNode.LINE) {
          LineNumberNode lineNumberNode = (LineNumberNode) node;
          return lineNumberNode.line;
        }
        node = node.getNext();
      }
      return -1;
    }

    public int getPreviousLine(LabelNode label, int minLine) {
      int index = insns.indexOf(label);
      AbstractInsnNode node = insns.get(index);
      while (node != null) {
        if (node.getType() == AbstractInsnNode.LINE) {
          LineNumberNode lineNumberNode = (LineNumberNode) node;
          if (lineNumberNode.line >= minLine) {
            return lineNumberNode.line;
          }
        }
        node = node.getPrevious();
      }
      return -1;
    }
  }

  private Map<Label, Integer> getMonotonicLineMap(MethodNode methodNode) {
    Map<Label, Integer> map = new HashMap<>();
    AbstractInsnNode node = methodNode.instructions.getFirst();
    int maxLine = 0;
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        LineNumberNode lineNumberNode = (LineNumberNode) node;
        maxLine = Math.max(lineNumberNode.line, maxLine);
      }
      if (node.getType() == AbstractInsnNode.LABEL) {
        LabelNode labelNode = (LabelNode) node;
        map.put(labelNode.getLabel(), maxLine);
      }
      node = node.getNext();
    }
    return map;
  }

  private void extractBlockScopes(MethodNode methodNode) {
    LineMap lineMap = getLineMap(methodNode);
    List<Block> blocks = new ArrayList<>();
    int currentLine = 0;
    for (AbstractInsnNode instruction : methodNode.instructions) {
      if (instruction.getType() == AbstractInsnNode.LINE) {
        currentLine = ((LineNumberNode) instruction).line;
        continue;
      }
      if (instruction.getType() == AbstractInsnNode.JUMP_INSN) {
        JumpInsnNode jumpInsnNode = (JumpInsnNode) instruction;
        int jumpTarget = lineMap.getLine(jumpInsnNode.label.getLabel()) - 1;

        // Jumps that go to labels without line number we ignore for the time being
        if (jumpTarget == -1) {
          continue;
        }

        // For-loops loop back to a previous line number for the comparison of i
        if (currentLine > jumpTarget) {
          jumpTarget = findNextValidLine(methodNode.instructions, jumpInsnNode.label.getLabel(), currentLine);
        }

        // Too small
        if (jumpTarget - currentLine <= 1) {
          continue;
        }

        blocks.add(new Block(currentLine, jumpTarget, Collections.emptyList()));
      }
    }
    System.out.println("blocks = " + blocks);
  }

  private int findNextValidLine(InsnList instructions, Label jumpLabel, int floor) {
    int index = getIndex(instructions, jumpLabel);
    AbstractInsnNode insn = instructions.get(index).getNext();
    while (insn != null) {
      if (insn.getType() == AbstractInsnNode.LINE) {
        int line = ((LineNumberNode) insn).line;
        if (line > floor) {
          return line;
        }
      }
      insn = insn.getNext();
    }
    return -1;
  }

  private static int getIndex(InsnList instructions, Label jumpLabel) {
    int index = 0;
    for (AbstractInsnNode instruction : instructions) {
      if (instruction.getType() == AbstractInsnNode.LABEL) {
        if (((LabelNode) instruction).getLabel() == jumpLabel) {
          break;
        }
      }
      index++;
    }
    return index;
  }

  private boolean isBlockStart(AbstractInsnNode ain) {
    return isIf(ain);
  }

  private boolean isIf(AbstractInsnNode ain) {
    return isIf(ain.getOpcode());
  }

  private boolean isIf(int opcode) {
    return opcode == Opcodes.IFEQ ||
        opcode == Opcodes.IFNE ||
        opcode == Opcodes.IFLT ||
        opcode == Opcodes.IFGE ||
        opcode == Opcodes.IFGT ||
        opcode == Opcodes.IFLE ||
        opcode == Opcodes.IFNONNULL ||
        opcode == Opcodes.IFNULL ||
        opcode == Opcodes.IF_ICMPEQ ||
        opcode == Opcodes.IF_ICMPNE ||
        opcode == Opcodes.IF_ICMPLT ||
        opcode == Opcodes.IF_ICMPGE ||
        opcode == Opcodes.IF_ICMPGT ||
        opcode == Opcodes.IF_ICMPLE ||
        opcode == Opcodes.IF_ACMPEQ ||
        opcode == Opcodes.IF_ACMPNE;
  }

  private boolean isBlockEnd(AbstractInsnNode ain) {
    return isJump(ain);
  }

  private boolean isJump(AbstractInsnNode ain) {
    return isJump(ain.getOpcode());
  }

  private boolean isJump(int opcode) {
    return opcode == Opcodes.GOTO || opcode == Opcodes.RETURN;
  }

  static class Block {
    private final int startLine;
    private final int endLine;
    private final List<Variable> variables;

    Block(int startLine, int endLine, List<Variable> variables) {
      this.startLine = startLine;
      this.endLine = endLine;
      this.variables = variables;
    }

    public int getStartLine() {
      return startLine;
    }

    public int getEndLine() {
      return endLine;
    }

    @Override
    public String toString() {
      return "Block{" +
          "startLine=" + startLine +
          ", endLine=" + endLine +
          ", variables=" + variables +
          '}';
    }
  }

  static class Variable {
    private final String name;
    private final int line;

    Variable(String name, int line) {
      this.name = name;
      this.line = line;
    }

    public String getName() {
      return name;
    }

    public int getLine() {
      return line;
    }

    @Override
    public String toString() {
      return "Variable{" +
          "name='" + name + '\'' +
          ", line=" + line +
          '}';
    }
  }


  private ClassNode parseClassFile(String classFilePath, byte[] classfileBuffer) {
    ClassReader reader = new ClassReader(classfileBuffer);
    ClassNode classNode = new ClassNode();
    reader.accept(classNode, ClassReader.SKIP_FRAMES);
    return classNode;
  }

  private ClassExtraction extractScopes(ClassNode classNode) {
//    LineMap lineMap = getLineMap(classNode);
//    List<MethodExtraction> methods = new ArrayList<>();
//    for (MethodNode method : classNode.methods) {
//      Map<Location, List<LocalVariableNode>> locals = new HashMap<>();
//      for (LocalVariableNode localVariable : method.localVariables) {
//        Location l = new Location(
//            lineMap.getLine(localVariable.start.getLabel()),
//            lineMap.getLine(localVariable.end.getLabel())
//        );
//        AbstractInsnNode node = method.instructions.getFirst();
//        locals.merge(l, new ArrayList<>(Collections.singletonList(localVariable)), (prev, next) -> {
//          prev.addAll(next);
//          return prev;
//        });
//      }
//      methods.add(new MethodExtraction(method.name, locals));
//    }
    return new ClassExtraction(classNode.name, classNode.sourceFile, Collections.emptyList());
  }

//  private LineMap getLineMap(ClassNode classNode) {
//    LineMap lineMap = new LineMap();
//    for (MethodNode methodNode : classNode.methods) {
//      getLineMap(lineMap, methodNode);
//    }
//    return lineMap;
//  }

  private static LineMap getLineMap(MethodNode methodNode) {
    LineMap lineMap = new LineMap();
    AbstractInsnNode node = methodNode.instructions.getFirst();
    while (node != null) {
      if (node.getType() == AbstractInsnNode.LINE) {
        lineMap.addLine((LineNumberNode) node);
      }
      node = node.getNext();
    }
    return lineMap;
  }

  public ClassExtraction getClassExtraction() {
    return classExtraction;
  }

  private static class Location {
    final int startLine;
    final int endLine;

    private Location(int startLine, int endLine) {
      this.startLine = startLine;
      this.endLine = endLine;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Location location = (Location) o;

      if (startLine != location.startLine) return false;
      return endLine == location.endLine;
    }

    @Override
    public int hashCode() {
      int result = startLine;
      result = 31 * result + endLine;
      return result;
    }

    @Override
    public String toString() {
      return "Location{" +
          "startLine=" + startLine +
          ", endLine=" + endLine +
          '}';
    }
  }

  public static class ClassExtraction {

    private final String className;

    private final String sourcePath;

    private final int startLine = 0;

    private final List<MethodExtraction> methods;

    private ClassExtraction(String className, String sourcePath, List<MethodExtraction> methods) {
      this.className = className;
      this.sourcePath = sourcePath;
      this.methods = methods;
    }

    public List<MethodExtraction> getMethods() {
      return methods;
    }

    public String getClassName() {
      return className;
    }

    public String getSourcePath() {
      return sourcePath;
    }

    @Override
    public String toString() {
      return "ClassExtraction{" +
          "className='" + className + '\'' +
          ", sourcePath='" + sourcePath + '\'' +
          ", methods=" + methods +
          '}';
    }
  }

  public static class MethodExtraction {

    private final String methodName;

    private final Map<Location, List<LocalVariableNode>> scopes;

    private MethodExtraction(String methodName, Map<Location, List<LocalVariableNode>> scopes) {
      this.methodName = methodName;
      this.scopes = scopes;
    }

    public Map<Location, List<LocalVariableNode>> getScopes() {
      return scopes;
    }

    public String getMethodName() {
      return methodName;
    }

    @Override
    public String toString() {
      return "MethodExtraction{" +
          "methodName='" + methodName + '\'' +
          ", scopes=" + scopes +
          '}';
    }
  }
}
