/*
 * Copyright (c) 2009, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package java.lang.module;

import java.io.InputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static jdk.internal.module.Checks.*;
import static java.util.Objects.*;

import jdk.internal.module.Checks;
import jdk.internal.module.Hasher.DependencyHashes;


/**
 * A module descriptor.
 *
 * <p> A {@code ModuleDescriptor} is typically created from the binary form
 * of a module declaration. The associated {@link ModuleDescriptor.Builder}
 * class can also be used to create a {@code ModuleDescriptor} from its
 * components. </p>
 *
 * <p> {@code ModuleDescriptor} objects are immutable and safe for use by
 * multiple concurrent threads.</p>
 *
 * @since 9
 * @see java.lang.reflect.Module
 */

public class ModuleDescriptor
    implements Comparable<ModuleDescriptor>
{

    /**
     * <p> A dependence upon a module </p>
     *
     * @see ModuleDescriptor#requires()
     * @since 9
     */

    public final static class Requires
        implements Comparable<Requires>
    {

        /**
         * A modifier on a module dependence.
         *
         * @since 9
         */
        public static enum Modifier {

            /**
             * The dependence causes any module which depends on the <i>current
             * module</i> to have an implicitly declared dependence on the module
             * named by the {@code Requires}.
             */
            PUBLIC,

            /**
             * The dependence was not explicitly or implicitly declared in the
             * source of the module declaration.
             */
            SYNTHETIC,

            /**
             * The dependence was implicitly declared in the source of the module
             * declaration.
             */
            MANDATED;

        }

        private final Set<Modifier> mods;
        private final String name;

        private Requires(Set<Modifier> ms, String mn) {
            this(ms, mn, true);
        }
        private Requires(Set<Modifier> ms, String mn, boolean check) {
            if (ms == null || ms.isEmpty()) {
                mods = Collections.emptySet();
            } else {
                mods = check ? Collections.unmodifiableSet(EnumSet.copyOf(ms))
                             : ms;
            }
            this.name = check ? requireModuleName(mn) : mn;
        }

        /**
         * Returns the set of modifiers.
         *
         * @return A possibly-empty unmodifiable set of modifiers
         */
        public Set<Modifier> modifiers() {
            return mods;
        }

        /**
         * Return the module name.
         *
         * @return The module name
         */
        public String name() {
            return name;
        }

        /**
         * Compares this module dependence to another.
         *
         * <p> Two {@code Requires} objects are compared by comparing their
         * module name lexicographically.  Where the module names are equal then
         * the sets of modifiers are compared.
         *
         * @return A negative integer, zero, or a positive integer if this module
         *         dependence is less than, equal to, or greater than the given
         *         module dependence
         */
        @Override
        public int compareTo(Requires that) {
            int c = this.name().compareTo(that.name());
            if (c != 0)
                return c;
            // same name, compare by modifiers
            return Long.compare(this.modsValue(), that.modsValue());
        }

        /**
         * Return a value for the modifiers to allow sets of modifiers to be
         * compared.
         */
        private long modsValue() {
            long value = 0;
            for (Modifier m : mods) {
                value += 1 << m.ordinal();
            }
            return value;
        }


        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Requires))
                return false;
            Requires that = (Requires)ob;
            return (name.equals(that.name) && mods.equals(that.mods));
        }

        @Override
        public int hashCode() {
            return name.hashCode() * 43 + mods.hashCode();
        }

        @Override
        public String toString() {
            return Dependence.toString(mods, name);
        }

    }



    /**
     * <p> A module export, may be qualified or unqualified. </p>
     *
     * @see ModuleDescriptor#exports()
     * @since 9
     */

    public final static class Exports {

        private final String source;
        private final Set<String> targets;

        private Exports(String source, Set<String> targets) {
            this(source, targets, true);
        }

        private Exports(String source, Set<String> targets, boolean check) {
            this.source = check ? requirePackageName(source) : source;
            targets = check ? Collections.unmodifiableSet(new HashSet<>(targets))
                            : Collections.unmodifiableSet(targets);
            if (targets.isEmpty())
                throw new IllegalArgumentException("Empty target set");
            if (check)
                targets.stream().forEach(Checks::requireModuleName);
            this.targets = targets;
        }

        /**
         * Constructs an {@code Exports} to represent the exporting of package
         * {@code source}.
         */
        private Exports(String source) {
            this(source, true);
        }
        private Exports(String source, boolean check) {
            this.source = check ? requirePackageName(source) : source;
            this.targets = null;
        }

        /**
         * Returns the package name.
         *
         * @return The package name
         */
        public String source() {
            return source;
        }

        /**
         * For a qualified export, returns the non-empty and immutable set
         * of the module names to which the package is exported. For an
         * unqualified export, returns an empty {@code Optional}.
         *
         * @apiNote An alternative is to introduce {@code isQualified()} and
         * change this method to an empty set when an exported is unqualified
         *
         * @return The set of target module names or for an unqualified
         *         export, an empty {@code Optional}
         */
        public Optional<Set<String>> targets() {
            return Optional.ofNullable(targets);
        }

        public int hashCode() {
            return hash(source, targets);
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Exports))
                return false;
            Exports other = (Exports)obj;
            return Objects.equals(this.source, other.source) &&
                Objects.equals(this.targets, other.targets);
        }

        public String toString() {
            if (targets != null)
                return source + " to " + targets;
            return source;
        }

    }



    /**
     * <p> A service that a module provides one or more implementations of. </p>
     *
     * @see ModuleDescriptor#provides()
     * @since 9
     */

    public final static class Provides {

        private final String service;
        private final Set<String> providers;

        private Provides(String service, Set<String> providers) {
            this(service, providers, true);
        }

        private Provides(String service, Set<String> providers, boolean check) {
            this.service = check ? requireServiceTypeName(service) : service;
            providers = check ? Collections.unmodifiableSet(new HashSet<>(providers))
                              : Collections.unmodifiableSet(providers);
            if (providers.isEmpty())
                throw new IllegalArgumentException("Empty providers set");
            if (check)
                providers.forEach(Checks::requireServiceProviderName);
            this.providers = providers;
        }

        /**
         * Returns the fully qualified class name of the service type.
         *
         * @return The fully qualified class name of the service type.
         */
        public String service() { return service; }

        /**
         * Returns the set of the fully qualified class names of the providers.
         *
         * @return A non-empty and unmodifiable set of the fully qualified class
         *         names of the providers.
         */
        public Set<String> providers() { return providers; }

        public int hashCode() {
            return hash(service, providers);
        }

        public boolean equals(Object obj) {
            if (!(obj instanceof Provides))
                return false;
            Provides other = (Provides)obj;
            return Objects.equals(this.service, other.service) &&
                    Objects.equals(this.providers, other.providers);
        }

    }



    /**
     * Vaguely Debian-like version strings, for now.
     * This will, eventually, change.
     *
     * @see <a href="http://www.debian.org/doc/debian-policy/ch-controlfields.html#s-f-Version">Debian
     * Policy Manual, Chapter 5: Control files and their fields</a>
     *
     * @see ModuleDescriptor#version()
     * @since 9
     */

    public final static class Version
        implements Comparable<Version>
    {

        private final String version;

        // If Java had disjunctive types then we'd write List<Integer|String> here
        //
        private final List<Object> sequence;
        private final List<Object> branch;

        // Take a numeric token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is (decimal) numeric
        //
        private static int takeNumber(String s, int i, List<Object> acc) {
            char c = s.charAt(i);
            int d = (c - '0');
            int n = s.length();
            while (++i < n) {
                c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    d = d * 10 + (c - '0');
                    continue;
                }
                break;
            }
            acc.add(d);
            return i;
        }

        // Take a string token starting at position i
        // Append it to the given list
        // Return the index of the first character not taken
        // Requires: s.charAt(i) is not '.'
        //
        private static int takeString(String s, int i, List<Object> acc) {
            int b = i;
            int n = s.length();
            while (++i < n) {
                char c = s.charAt(i);
                if (c != '.' && c != '-' && !(c >= '0' && c <= '9'))
                    continue;
                break;
            }
            acc.add(s.substring(b, i));
            return i;
        }

        // Version syntax, for now: tok+ ( '-' tok+)?
        // First token string is sequence, second is branch
        // Tokens are delimited by '.', or by changes between alpha & numeric
        // chars
        // Numeric tokens are compared as decimal numbers
        // Non-numeric tokens are compared lexicographically
        // Tokens in branch may contain '-'
        //
        private Version(String v) {

            if (v == null)
                throw new IllegalArgumentException("Null version string");
            int n = v.length();
            if (n == 0)
                throw new IllegalArgumentException("Empty version string");

            int i = 0;
            char c = v.charAt(i);
            if (!(c >= '0' && c <= '9'))
                throw new
                        IllegalArgumentException(v
                        + ": Version does not start"
                        + " with a number");

            List<Object> sequence = new ArrayList<>(4);
            List<Object> branch = new ArrayList<>(2);

            i = takeNumber(v, i, sequence);

            while (i < n) {
                c = v.charAt(i);
                if (c == '.') {
                    i++;
                    continue;
                }
                if (c == '-') {
                    i++;
                    break;
                }
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, sequence);
                else
                    i = takeString(v, i, sequence);
            }

            if (c == '-' && i >= n)
                throw new IllegalArgumentException(v + ": Empty branch");

            while (i < n) {
                c = v.charAt(i);
                if (c >= '0' && c <= '9')
                    i = takeNumber(v, i, branch);
                else
                    i = takeString(v, i, branch);
                if (i >= n)
                    break;
                c = v.charAt(i);
                if (c == '.') {
                    i++;
                    continue;
                }
            }

            this.version = v;
            this.sequence = sequence;
            this.branch = branch;
        }

        /**
         * Parses the given string as a version string.
         *
         * @param  v
         *         The string to parse as a version string
         *
         * @return The resulting {@code Version}
         *
         * @throws IllegalArgumentException
         *         If {@code v} is {@code null}, an empty string, or cannot be
         *         parsed as a version string
         */
        public static Version parse(String v) {
            return new Version(v);
        }

        @SuppressWarnings("unchecked")
        private int cmp(Object o1, Object o2) {
            return ((Comparable)o1).compareTo(o2);
        }

        private int compareTokens(List<Object> ts1, List<Object> ts2) {
            int n = Math.min(ts1.size(), ts2.size());
            for (int i = 0; i < n; i++) {
                Object o1 = ts1.get(i);
                Object o2 = ts2.get(i);
                if (   (o1 instanceof Integer && o2 instanceof Integer)
                        || (o1 instanceof String && o2 instanceof String)) {
                    int c = cmp(o1, o2);
                    if (c == 0)
                        continue;
                    return c;
                }
                // Types differ, so convert number to string form
                int c = o1.toString().compareTo(o2.toString());
                if (c == 0)
                    continue;
                return c;
            }
            List<Object> rest = ts1.size() > ts2.size() ? ts1 : ts2;
            int e = rest.size();
            for (int i = n; i < e; i++) {
                Object o = rest.get(i);
                if (o instanceof Integer && ((Integer)o) == 0)
                    continue;
                return ts1.size() - ts2.size();
            }
            return 0;
        }

        @Override
        public int compareTo(Version that) {
            int c = compareTokens(this.sequence, that.sequence);
            if (c != 0)
                return c;
            return compareTokens(this.branch, that.branch);
        }

        @Override
        public boolean equals(Object ob) {
            if (!(ob instanceof Version))
                return false;
            return compareTo((Version)ob) == 0;
        }

        @Override
        public int hashCode() {
            return version.hashCode();
        }

        @Override
        public String toString() {
            return version;
        }

    }



    // From module declarations
    private final String name;
    private final Set<Requires> requires;
    private final Set<Exports> exports;
    private final Set<String> uses;
    private final Map<String, Provides> provides;

    // Indicates if synthesised for a JAR file found on the module path
    private final boolean automatic;

    // Not generated from a module-info.java
    private final boolean synthetic;

    // "Extended" information, added post-compilation by tools
    private final Version version;
    private final String mainClass;
    private final String osName;
    private final String osArch;
    private final String osVersion;
    private final Set<String> conceals;
    private final Set<String> packages;
    private final DependencyHashes hashes;

    private ModuleDescriptor(String name,
                             boolean automatic,
                             boolean synthetic,
                             Map<String, Requires> requires,
                             Set<String> uses,
                             Map<String, Exports> exports,
                             Map<String, Provides> provides,
                             Version version,
                             String mainClass,
                             String osName,
                             String osArch,
                             String osVersion,
                             Set<String> conceals,
                             DependencyHashes hashes)
    {

        this.name = name;
        this.automatic = automatic;
        this.synthetic = synthetic;

        Set<Requires> rqs = new HashSet<>(requires.values());
        assert (rqs.stream().map(Requires::name).sorted().distinct().count()
                == rqs.size())
            : "Module " + name + " has duplicate requires";
        this.requires = emptyOrUnmodifiableSet(rqs);

        Set<Exports> exs = new HashSet<>(exports.values());
        assert (exs.stream().map(Exports::source).sorted().distinct().count()
                == exs.size())
            : "Module " + name + " has duplicate exports";
        this.exports = emptyOrUnmodifiableSet(exs);

        this.uses = emptyOrUnmodifiableSet(uses);
        this.provides = emptyOrUnmodifiableMap(provides);

        this.version = version;
        this.mainClass = mainClass;
        this.osName = osName;
        this.osArch = osArch;
        this.osVersion = osVersion;
        this.hashes = hashes;

        assert !exports.keySet().stream().anyMatch(conceals::contains)
            : "Module " + name + ": Package sets overlap";
        this.conceals = emptyOrUnmodifiableSet(conceals);
        this.packages = computePackages(this.exports, this.conceals);
    }

    /**
     * Clones the given module descriptor with an augmented set of packages
     */
    ModuleDescriptor(ModuleDescriptor md, Set<String> pkgs) {
        this.name = md.name;
        this.automatic = md.automatic;
        this.synthetic = md.synthetic;

        this.requires = md.requires;
        this.exports = md.exports;
        this.uses = md.uses;
        this.provides = md.provides;

        this.version = md.version;
        this.mainClass = md.mainClass;
        this.osName = md.osName;
        this.osArch = md.osArch;
        this.osVersion = md.osVersion;
        this.hashes = null; // need to ignore

        this.packages = emptyOrUnmodifiableSet(pkgs);
        this.conceals = computeConcealedPackages(this.exports, this.packages);
    }

    /**
     * Creates a module descriptor from its components. This method is intended
     * for use by the jlink plugin.
     */
    ModuleDescriptor(String name,
                     boolean automatic,
                     boolean synthetic,
                     Set<Requires> requires,
                     Set<String> uses,
                     Set<Exports> exports,
                     Map<String, Provides> provides,
                     Version version,
                     String mainClass,
                     String osName,
                     String osArch,
                     String osVersion,
                     Set<String> conceals,
                     Set<String> packages) {
        this.name = name;
        this.automatic = automatic;
        this.synthetic = synthetic;
        this.requires = Collections.unmodifiableSet(requires);
        this.exports = Collections.unmodifiableSet(exports);
        this.uses = Collections.unmodifiableSet(uses);
        this.provides = Collections.unmodifiableMap(provides);
        this.conceals = Collections.unmodifiableSet(conceals);
        this.packages = Collections.unmodifiableSet(packages);

        this.version = version;
        this.mainClass = mainClass;
        this.osName = osName;
        this.osArch = osArch;
        this.osVersion = osVersion;
        this.hashes = null;
    }

    /**
     * <p> The module name. </p>
     *
     * @return The module name
     */
    public String name() {
        return name;
    }

    /**
     * <p> Returns {@code true} if this is an automatic module. </p>
     *
     * <p> An automatic module is defined implicitly rather than explicitly
     * and therefore does not have a module declaration. JAR files located on
     * the application module path, or by the {@link ModuleFinder} returned by
     * {@link ModuleFinder#of(java.nio.file.Path[]) ModuleFinder.of}, are
     * treated as automatic modules if they do not have a compiled module
     * declaration ({@code module-info.class}). </p>
     *
     * @return  {@code true} if this is an automatic module
     */
    public boolean isAutomatic() {
        return automatic;
    }

    /**
     * <p> Returns {@code true} if this module descriptor was not originally
     * compiled from source code. </p>
     *
     * <p> This method always returns {@code true} for {@link #isAutomatic()
     * automatic} modules or {@code ModuleDescriptor} objects created
     * programmatically using a {@link Builder}. </p>
     *
     * @return  {@code true} if this module descriptor was not originally
     *          compiled from source code.
     *
     * @jvms 4.7.8 The {@code Synthetic} Attribute
     */
    public boolean isSynthetic() {
        return synthetic;
    }

    /**
     * <p> The dependences of this module. </p>
     *
     * @return  A possibly-empty unmodifiable set of {@link Requires} objects
     */
    public Set<Requires> requires() {
        return requires;
    }

    /**
     * <p> The service dependences of this module. </p>
     *
     * @return  A possibly-empty unmodifiable set of the fully qualified class
     *          names of the service types used
     */
    public Set<String> uses() {
        return uses;
    }

    /**
     * <p> The services that this module provides. </p>
     *
     * @return The possibly-empty unmodifiable map of the services that this
     *         module provides. The map key is fully qualified class name of
     *         the service type.
     */
    public Map<String, Provides> provides() {
        return provides;
    }

    /**
     * <p> The module exports. </p>
     *
     * @return  A possibly-empty unmodifiable set of exported packages
     */
    public Set<Exports> exports() {
        return exports;
    }

    /**
     * Returns this module's version.
     *
     * @return This module's version
     */
    public Optional<Version> version() {
        return Optional.ofNullable(version);
    }

    /**
     * Returns a string containing this module's name and, if present, its
     * version.
     *
     * @return A string containing this module's name and, if present, its
     *         version.
     */
    public String toNameAndVersion() {
        if (version != null) {
            return name() + "@" + version;
        } else {
            return name();
        }
    }

    /**
     * Returns the module's main class.
     *
     * @return The fully qualified class name of this module's main class
     */
    public Optional<String> mainClass() {
        return Optional.ofNullable(mainClass);
    }

    /**
     * Returns the operating system name if this module is operating system
     * specific.
     *
     * @return The operating system name or an empty {@code Optional}
     *         if this module is not operating system specific
     */
    public Optional<String> osName() {
        return Optional.ofNullable(osName);
    }

    /**
     * Returns the operating system architecture if this module is operating
     * system architecture specific.
     *
     * @return The operating system architecture or an empty {@code Optional}
     *         if this module is not operating system architecture specific
     */
    public Optional<String> osArch() {
        return Optional.ofNullable(osArch);
    }

    /**
     * Returns the operating system version if this module is operating
     * system version specific.
     *
     * @return The operating system version or an empty {@code Optional}
     *         if this module is not operating system version specific
     */
    public Optional<String> osVersion() {
        return Optional.ofNullable(osVersion);
    }

    /**
     * Returns the names of the packages defined in, but not exported by, this
     * module.
     *
     * @return A possibly-empty unmodifiable set of the concealed packages
     */
    public Set<String> conceals() {
        return conceals;
    }

    /**
     * Returns the names of all the packages defined in this module, whether
     * exported or concealed.
     *
     * @return A possibly-empty unmodifiable set of the all packages
     */
    public Set<String> packages() {
        return packages;
    }

    /**
     * Returns the object with the hashes of the dependences.
     */
    Optional<DependencyHashes> hashes() {
        return Optional.ofNullable(hashes);
    }


    /**
     * A builder used for building {@link ModuleDescriptor} objects.
     *
     * <p> Example usage: </p>
     *
     * <pre>{@code
     *     ModuleDescriptor descriptor = new ModuleDescriptor.Builder("m1")
     *         .requires("m2")
     *         .exports("p")
     *         .build();
     * }</pre>
     * @since 9
     */
    public static final class Builder {

        final String name;
        final boolean automatic;
        boolean synthetic;
        boolean syntheticSet;
        final Map<String, Requires> requires = new HashMap<>();
        final Set<String> uses = new HashSet<>();
        final Map<String, Exports> exports = new HashMap<>();
        final Map<String, Provides> provides = new HashMap<>();
        Set<String> conceals = Collections.emptySet();
        Version version;
        String osName;
        String osArch;
        String osVersion;
        String mainClass;
        DependencyHashes hashes;

        /**
         * Initializes a new builder with the given module name.
         *
         * @param  name
         *         The module name
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null} or is not a legal Java
         *         identifier
         */
        public Builder(String name) {
            this(name, false);
        }

        /* package */ Builder(String name, boolean automatic) {
            this.name = requireModuleName(name);
            this.automatic = automatic;
        }

        /**
         * Adds a module dependence with the given (and possibly empty) set
         * of modifiers.
         *
         * @param  mods
         *         The set of modifiers
         * @param  mn
         *         The module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal Java
         *         identifier, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If a dependency on the module has already been declared
         */
        public Builder requires(Set<Requires.Modifier> mods, String mn) {
            if (name.equals(mn))
                throw new IllegalArgumentException("Dependence on self");
            if (requires.containsKey(mn))
                throw new IllegalStateException("Dependence upon " + mn
                                                + " already declared");
            requires.put(mn, new Requires(mods, mn)); // checks mn
            return this;
        }

        /**
         * Adds a module dependence with an empty set of modifiers.
         *
         * @param  mn
         *         The module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal Java
         *         identifier, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If a dependency on the module has already been declared
         */
        public Builder requires(String mn) {
            return requires(EnumSet.noneOf(Requires.Modifier.class), mn);
        }

        /**
         * Adds a module dependence with the given modifier.
         *
         * @param  mod
         *         The modifier
         * @param  mn
         *         The module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the module name is {@code null}, is not a legal Java
         *         identifier, or is equal to the module name that this builder
         *         was initialized to build
         * @throws IllegalStateException
         *         If a dependency on the module has already been declared
         */
        public Builder requires(Requires.Modifier mod, String mn) {
            return requires(EnumSet.of(mod), mn);
        }

        /**
         * Adds a service dependence.
         *
         * @param  st
         *         The service type
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the service type is {@code null} or is not a legal Java
         *         identifier
         * @throws IllegalStateException
         *         If a dependency on the service type has already been declared
         */
        public Builder uses(String st) {
            if (uses.contains(requireServiceTypeName(st)))
                throw new IllegalStateException("Dependence upon service "
                                                + st + " already declared");
            uses.add(st);
            return this;
        }

        /**
         * Ensures that the given package name has not been declared as an
         * exported or concealed package.
         */
        private void ensureNotExportedOrConcealed(String pn) {
            if (exports.containsKey(pn))
                throw new IllegalStateException("Export of package "
                                                + pn + " already declared");
            if (conceals.contains(pn))
                throw new IllegalStateException("Concealed package "
                                                + pn + " already declared");
        }

        /**
         * Adds an export to a set of target modules.
         *
         * @param  pn
         *         The package name
         * @param  targets
         *         The set of target modules names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name or any of the target modules is {@code
         *         null} or is not a legal Java identifier, or the set of
         *         targets is empty
         * @throws IllegalStateException
         *         If the package is already declared as an exported or
         *         concealed package
         */
        public Builder exports(String pn, Set<String> targets) {
            ensureNotExportedOrConcealed(pn);
            exports.put(pn, new Exports(pn, targets)); // checks pn and targets
            return this;
        }

        /**
         * Adds an export to a target module.
         *
         * @param  pn
         *         The package name
         * @param  target
         *         The target module name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name or target module is {@code null} or is
         *         not a legal Java identifier
         * @throws IllegalStateException
         *         If the package is already declared as an exported or
         *         concealed package
         */
        public Builder exports(String pn, String target) {
            return exports(pn, Collections.singleton(target));
        }

        /**
         * Adds an export.
         *
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null} or is not a legal Java
         *         identifier
         * @throws IllegalStateException
         *         If the package is already declared as an exported or
         *         concealed package
         */
        public Builder exports(String pn) {
            ensureNotExportedOrConcealed(pn);
            exports.put(pn, new Exports(pn)); // checks pn
            return this;
        }

        // Used by ModuleInfo, after a packageFinder is invoked
        /* package */ Set<String> exportedPackages() {
            return exports.keySet();
        }

        /**
         * Provides service {@code st} with implementations {@code pcs}.
         *
         * @param  st
         *         The service type
         * @param  pcs
         *         The set of provider class names
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the service type or any of the provider class names is
         *         {@code null} or is not a legal Java identifier, or the set
         *         of provider class names is empty
         * @throws IllegalStateException
         *         If the providers for the service type have already been
         *         declared
         */
        public Builder provides(String st, Set<String> pcs) {
            if (provides.containsKey(st))
                throw new IllegalStateException("Providers of service "
                                                + st + " already declared");
            provides.put(st, new Provides(st, pcs)); // checks st and pcs
            return this;
        }

        /**
         * Provides service {@code st} with implementation {@code pc}.
         *
         * @param  st
         *         The service type
         * @param  pc
         *         The provider class name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the service type or the provider class name is {@code
         *         null} or is not a legal Java identifier
         * @throws IllegalStateException
         *         If the providers for the service type have already been
         *         declared
         */
        public Builder provides(String st, String pc) {
            return provides(st, Collections.singleton(pc));
        }

        /**
         * Adds a set of (possible empty) concealed packages.
         *
         * @param  pns
         *         The set of package names of the concealed packages
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If any of the package names is {@code null} or is not a
         *         legal Java identifier
         * @throws IllegalStateException
         *         If any of packages are already declared as a concealed or
         *         exported package
         */
        public Builder conceals(Set<String> pns) {
            pns.forEach(this::conceals);
            return this;
        }

        /**
         * Adds a concealed package.
         *
         * @param  pn
         *         The package name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If the package name is {@code null}, or is not a legal Java
         *         identifier
         * @throws IllegalStateException
         *         If the package is already declared as a concealed or exported
         *         package
         */
        public Builder conceals(String pn) {
            Checks.requirePackageName(pn);
            ensureNotExportedOrConcealed(pn);
            if (conceals.isEmpty())
                conceals = new HashSet<>();
            conceals.add(pn);
            return this;
        }

        /**
         * Sets the module version.
         *
         * @param  v
         *         The version string to parse
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code v} is null or cannot be parsed as a version string
         * @throws IllegalStateException
         *         If the module version is already set
         *
         * @see Version#parse(String)
         */
        public Builder version(String v) {
            if (version != null)
                throw new IllegalStateException("module version already set");
            version = Version.parse(v);
            return this;
        }

        /**
         * Sets the module main class.
         *
         * @param  mc
         *         The module main class
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code mainClass} is null or is not a legal Java identifier
         * @throws IllegalStateException
         *         If the module main class is already set
         */
        public Builder mainClass(String mc) {
            if (mainClass != null)
                throw new IllegalStateException("main class already set");
            mainClass = requireJavaIdentifier("main class name", mc);
            return this;
        }

        /**
         * Sets the operating system name.
         *
         * @param  name
         *         The operating system name
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is null or the empty String
         * @throws IllegalStateException
         *         If the operating system name is already set
         */
        public Builder osName(String name) {
            if (osName != null)
                throw new IllegalStateException("OS name already set");
            if (name == null || name.isEmpty())
                throw new IllegalArgumentException("OS name is null or empty");
            osName = name;
            return this;
        }

        /**
         * Sets the operating system architecture.
         *
         * @param  arch
         *         The operating system architecture
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is null or the empty String
         * @throws IllegalStateException
         *         If the operating system architecture is already set
         */
        public Builder osArch(String arch) {
            if (osArch != null)
                throw new IllegalStateException("OS arch already set");
            if (arch == null || arch.isEmpty())
                throw new IllegalArgumentException("OS arch is null or empty");
            osArch = arch;
            return this;
        }

        /**
         * Sets the operating system version.
         *
         * @param  version
         *         The operating system version
         *
         * @return This builder
         *
         * @throws IllegalArgumentException
         *         If {@code name} is null or the empty String
         * @throws IllegalStateException
         *         If the operating system version is already set
         */
        public Builder osVersion(String version) {
            if (osVersion != null)
                throw new IllegalStateException("OS version already set");
            if (version == null || version.isEmpty())
                throw new IllegalArgumentException("OS version is null or empty");
            osVersion = version;
            return this;
        }

        /* package */ Builder hashes(DependencyHashes hashes) {
            this.hashes = hashes;
            return this;
        }


        /* package */ Builder synthetic(boolean v) {
            this.synthetic = v;
            this.syntheticSet = true;
            return this;
        }

        /**
         * Builds and returns a {@code ModuleDescriptor} from its components.
         *
         * @return The module descriptor
         */
        public ModuleDescriptor build() {
            assert name != null;

            // assume synthetic if not set
            boolean isSynthetic = (syntheticSet) ? synthetic : true;

            return new ModuleDescriptor(name,
                                        automatic,
                                        isSynthetic,
                                        requires,
                                        uses,
                                        exports,
                                        provides,
                                        version,
                                        mainClass,
                                        osName,
                                        osArch,
                                        osVersion,
                                        conceals,
                                        hashes);
        }

    }


    /**
     * Compares this module descriptor to another.
     *
     * <p> Two {@code ModuleDescriptor} objects are compared by comparing their
     * module name lexicographically.  Where the module names are equal then
     * the versions, if present, are compared.
     *
     * @param  that
     *         The object to which this module descriptor is to be compared
     *
     * @return A negative integer, zero, or a positive integer if this module
     *         descriptor is less than, equal to, or greater than the given
     *         module descriptor
     */
    @Override
    public int compareTo(ModuleDescriptor that) {
        int c = this.name().compareTo(that.name());
        if (c != 0) return c;
        if (version == null) {
            if (that.version == null)
                return 0;
            return -1;
        }
        if (that.version == null)
            return +1;
        return version.compareTo(that.version);
    }

    @Override
    public boolean equals(Object ob) {
        if (ob == this)
            return true;
        if (!(ob instanceof ModuleDescriptor))
            return false;
        ModuleDescriptor that = (ModuleDescriptor)ob;
        return (name.equals(that.name)
                && automatic == that.automatic
                && synthetic == that.synthetic
                && requires.equals(that.requires)
                && uses.equals(that.uses)
                && exports.equals(that.exports)
                && provides.equals(that.provides)
                && Objects.equals(version, that.version)
                && Objects.equals(mainClass, that.mainClass)
                && Objects.equals(osName, that.osName)
                && Objects.equals(osArch, that.osArch)
                && Objects.equals(osVersion, that.osVersion)
                && Objects.equals(conceals, that.conceals)
                && Objects.equals(hashes, that.hashes));
    }

    private transient int hash;  // cached hash code

    @Override
    public int hashCode() {
        int hc = hash;
        if (hc == 0) {
            hc = name.hashCode();
            hc = hc * 43 + Boolean.hashCode(automatic);
            hc = hc * 43 + Boolean.hashCode(synthetic);
            hc = hc * 43 + requires.hashCode();
            hc = hc * 43 + uses.hashCode();
            hc = hc * 43 + exports.hashCode();
            hc = hc * 43 + provides.hashCode();
            hc = hc * 43 + Objects.hashCode(version);
            hc = hc * 43 + Objects.hashCode(mainClass);
            hc = hc * 43 + Objects.hashCode(osName);
            hc = hc * 43 + Objects.hashCode(osArch);
            hc = hc * 43 + Objects.hashCode(osVersion);
            hc = hc * 43 + Objects.hashCode(conceals);
            hc = hc * 43 + Objects.hashCode(hashes);
            if (hc != 0) hash = hc;
        }
        return hc;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Module { name: ").append(toNameAndVersion());
        if (!requires.isEmpty())
            sb.append(", ").append(requires);
        if (!uses.isEmpty())
            sb.append(", ").append(uses);
        if (!exports.isEmpty())
            sb.append(", exports: ").append(exports);
        if (!provides.isEmpty()) {
            sb.append(", provides: [");
            for (Map.Entry<String, Provides> entry : provides.entrySet()) {
                sb.append(entry.getKey())
                   .append(" with ")
                   .append(entry.getValue());
            }
            sb.append("]");
        }
        sb.append(" }");
        return sb.toString();
    }

    /**
     * Reads a module descriptor from an input stream.
     *
     * <p> If the descriptor encoded in the input stream does not indicate a
     * set of concealed packages then the {@code packageFinder} will be
     * invoked.  The packages it returns, except for those indicated as
     * exported in the encoded descriptor, will be considered to be concealed.
     * If the {@code packageFinder} throws an {@link UncheckedIOException} then
     * the original {@link IOException} will be re-thrown.
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of concealed packages in the descriptor itself.
     *
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with a {@code InvalidModuleDescriptorException} or {@code
     * IOException} then it may do so after some, but not all, bytes have
     * been read from the input stream. It is strongly recommended that the
     * stream be promptly closed and discarded if an exception occurs.
     *
     * @param  in
     *         The input stream
     * @param  packageFinder
     *         A supplier that can produce a set of package names
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     * @throws IOException
     *         If an I/O error occurs reading from the input stream or {@code
     *         UncheckedIOException} is thrown by the package finder
     */
    public static ModuleDescriptor read(InputStream in,
                                        Supplier<Set<String>> packageFinder)
        throws IOException
    {
        return ModuleInfo.read(in, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from an input stream.
     *
     * @param  in
     *         The input stream
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     * @throws IOException
     *         If an I/O error occurs reading from the input stream
     */
    public static ModuleDescriptor read(InputStream in) throws IOException {
        return ModuleInfo.read(in, null);
    }

    /**
     * Reads a module descriptor from a byte buffer.
     *
     * <p> If the descriptor encoded in the byte buffer does not indicate a
     * set of concealed packages then the {@code packageFinder} will be
     * invoked.  The packages it returns, except for those indicated as
     * exported in the encoded descriptor, will be considered to be concealed.
     *
     * <p> The module descriptor is read from the buffer stating at index
     * {@code p}, where {@code p} is the buffer's {@link ByteBuffer#position()
     * position} when this method is invoked. Upon return the buffer's position
     * will be equal to {@code p + n} where {@code n} is the number of bytes
     * read from the buffer.
     *
     * @apiNote The {@code packageFinder} parameter is for use when reading
     * module descriptors from legacy module-artifact formats that do not
     * record the set of concealed packages in the descriptor itself.
     *
     * <p> If there are bytes following the module descriptor then it is
     * implementation specific as to whether those bytes are read, ignored,
     * or reported as an {@code InvalidModuleDescriptorException}. If this
     * method fails with an {@code InvalidModuleDescriptorException} then it
     * may do so after some, but not all,
     * bytes have been read.
     *
     * @param  bb
     *         The byte buffer
     * @param  packageFinder
     *         A supplier that can produce a set of package names
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb,
                                        Supplier<Set<String>> packageFinder)
    {
        return ModuleInfo.read(bb, requireNonNull(packageFinder));
    }

    /**
     * Reads a module descriptor from a byte buffer.
     *
     * @param  bb
     *         The byte buffer
     *
     * @return The module descriptor
     *
     * @throws InvalidModuleDescriptorException
     *         If an invalid module descriptor is detected
     */
    public static ModuleDescriptor read(ByteBuffer bb) {
        return ModuleInfo.read(bb, null);
    }


    /**
     * Computes the set of packages from exports and concealed packages.
     * It returns the concealed packages set if there is no exported package.
     */
    private static Set<String> computePackages(Set<Exports> exports,
                                               Set<String> conceals)
    {
        if (exports.isEmpty())
            return conceals;

        Set<String> pkgs = new HashSet<>(conceals);
        exports.stream().map(Exports::source).forEach(pkgs::add);
        return emptyOrUnmodifiableSet(pkgs);
    }

    /**
     * Computes the set of concealed packages from exports and all packages.
     * It returns the packages set if there are no exported packages.
     */
    private static Set<String> computeConcealedPackages(Set<Exports> exports,
                                                        Set<String> pkgs)
    {
        if (exports.isEmpty())
            return pkgs;

        Set<String> conceals = new HashSet<>(pkgs);
        exports.stream().map(Exports::source).forEach(conceals::remove);
        return emptyOrUnmodifiableSet(conceals);
    }

    private static <K,V> Map<K,V> emptyOrUnmodifiableMap(Map<K,V> map) {
        if (map.isEmpty()) {
            return Collections.emptyMap();
        } else if (map.size() == 1) {
            Map.Entry<K, V> entry = map.entrySet().iterator().next();
            return Collections.singletonMap(entry.getKey(), entry.getValue());
        } else {
            return Collections.unmodifiableMap(map);
        }
    }

    private static <T> Set<T> emptyOrUnmodifiableSet(Set<T> set) {
        if (set.isEmpty()) {
            return Collections.emptySet();
        } else if (set.size() == 1) {
            return Collections.singleton(set.iterator().next());
        } else {
            return Collections.unmodifiableSet(set);
        }
    }

    static {
        /**
         * Setup the shared secret to allow code in other packages create
         * ModuleDescriptor and associated objects directly.
         */
        jdk.internal.misc.SharedSecrets
            .setJavaLangModuleAccess(new jdk.internal.misc.JavaLangModuleAccess() {
                @Override
                public Requires newRequires(Set<Requires.Modifier> ms, String mn) {
                    return new Requires(ms, mn, false);
                }

                @Override
                public Exports newExports(String source, Set<String> targets) {
                    return new Exports(source, targets, false);
                }

                @Override
                public Exports newExports(String source) {
                    return new Exports(source, false);
                }

                @Override
                public Provides newProvides(String service, Set<String> providers) {
                    return new Provides(service, providers, false);
                }

                @Override
                public Version newVersion(String v) {
                    return new Version(v);
                }

                @Override
                public ModuleDescriptor newModuleDescriptor(ModuleDescriptor md,
                                                            Set<String> pkgs) {
                    return new ModuleDescriptor(md, pkgs);
                }

                @Override
                public ModuleDescriptor newModuleDescriptor(String name,
                                                            boolean automatic,
                                                            boolean synthetic,
                                                            Set<Requires> requires,
                                                            Set<String> uses, Set<Exports> exports,
                                                            Map<String, Provides> provides,
                                                            Version version,
                                                            String mainClass,
                                                            String osName,
                                                            String osArch,
                                                            String osVersion,
                                                            Set<String> conceals,
                                                            Set<String> packages) {
                    return new ModuleDescriptor(name,
                                                automatic,
                                                synthetic,
                                                requires,
                                                uses,
                                                exports,
                                                provides,
                                                version,
                                                mainClass,
                                                osName,
                                                osArch,
                                                osVersion,
                                                conceals,
                                                packages);
                }
            });
    }

}
