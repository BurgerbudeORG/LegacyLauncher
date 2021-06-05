package net.minecraft.launchwrapper;

/**
 * A transformer that transforms the names of classes. Please not that even though this interface
 * doesn't extend {@link IClassTransformer}, name transformers registered to the class loader must
 * implement it.
 *
 * @author Eirk Broes
 */
public interface IClassNameTransformer {

  /**
   * Unmaps a class name.
   *
   * @param name The original name of the class
   * @return The unmapped name, which will become the name passed to {@link
   *     IClassTransformer#transform(String, String, byte[])}
   */
  String unmapClassName(String name);

  /**
   * Remaps a class name.
   *
   * @param name The original name of the class.
   * @return The remapped name, which will become the transformedName passed to {@link
   *     IClassTransformer#transform(String, String, byte[])}
   */
  String remapClassName(String name);
}
