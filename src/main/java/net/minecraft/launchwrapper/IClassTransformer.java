package net.minecraft.launchwrapper;

/**
 * A transformer that modifies bytecode of classes loaded by the class loader.
 *
 * @author Erik Broes
 */
public interface IClassTransformer {

  /**
   * Transforms the bytecode of a class.
   *
   * @param name The untransformed name of the class. which is the result of {@link
   *     IClassNameTransformer#unmapClassName(String)}
   * @param transformedName The transformed name of the class, which is the result of {@link
   *     IClassNameTransformer#remapClassName(String)}
   * @param classData The bytecode of teh class to transform
   * @return The transformed bytecode.
   */
  byte[] transform(String name, String transformedName, byte... classData);
}
