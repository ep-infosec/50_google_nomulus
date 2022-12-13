// Copyright 2019 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.gradle.plugin;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.Iterables.getOnlyElement;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Streams;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Utility functions used in the GCS plugin. */
final class GcsPluginUtils {

  private static final ImmutableMap<String, String> EXTENSION_TO_CONTENT_TYPE =
      new ImmutableMap.Builder<String, String>()
          .put("html", "text/html")
          .put("htm", "text/html")
          .put("log", "text/plain")
          .put("txt", "text/plain")
          .put("css", "text/css")
          .put("xml", "text/xml")
          .put("zip", "application/zip")
          .put("js", "text/javascript")
          .build();

  private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

  static Path toNormalizedPath(File file) {
    return file.toPath().toAbsolutePath().normalize();
  }

  static Path toNormalizedPath(Path file) {
    return file.toAbsolutePath().normalize();
  }

  static String getContentType(String fileName) {
    return EXTENSION_TO_CONTENT_TYPE.getOrDefault(
        Files.getFileExtension(fileName), DEFAULT_CONTENT_TYPE);
  }

  static void uploadFileToGcs(
      Storage storage, String bucket, Path path, Supplier<byte[]> dataSupplier) {
    // Replace Windows file separators with forward slashes.
    String filename = path.toString().replace(File.separator, "/");
    storage.create(
        BlobInfo.newBuilder(bucket, filename).setContentType(getContentType(filename)).build(),
        dataSupplier.get());
  }

  static void uploadFilesToGcsMultithread(
      Storage storage, String bucket, Path folder, Map<Path, Supplier<byte[]>> files) {
    ImmutableMap.Builder<Path, Thread> threads = new ImmutableMap.Builder<>();
    files.forEach(
        (path, dataSupplier) -> {
          Thread thread =
              new Thread(
                  () -> uploadFileToGcs(storage, bucket, folder.resolve(path), dataSupplier));
          thread.start();
          threads.put(path, thread);
        });
    threads
        .build()
        .forEach(
            (path, thread) -> {
              try {
                thread.join();
              } catch (InterruptedException e) {
                System.out.format("Upload of %s interrupted", path);
              }
            });
  }

  static Supplier<byte[]> toByteArraySupplier(String data) {
    return () -> data.getBytes(UTF_8);
  }

  static Supplier<byte[]> toByteArraySupplier(Supplier<String> dataSupplier) {
    return () -> dataSupplier.get().getBytes(UTF_8);
  }

  static Supplier<byte[]> toByteArraySupplier(File file) {
    return () -> {
      try {
        return Files.toByteArray(file);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  static Supplier<byte[]> toByteArraySupplier(URL url) {
    return () -> {
      try {
        return Resources.toByteArray(url);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    };
  }

  /**
   * Reads all the files generated by a Report into a FilesWithEntryPoint object.
   *
   * <p>Every FilesWithEntryPoint must have a single link "entry point" that gives users access to
   * all the files. If the report generated just one file - we will just link to that file.
   *
   * <p>However, if the report generated more than one file - the only thing we can safely do is to
   * zip all the files and link to the zip file.
   *
   * <p>As an alternative to using a zip file, we allow the caller to supply an optional "entry
   * point" file that will link to all the other files. If that file is given and is "appropriate"
   * (exists and is in the correct location) - we will upload all the report files "as is" and link
   * to the entry file.
   *
   * @param destination the location of the output. Either a file or a directory. If a directory -
   *     then all the files inside that directory are the outputs we're looking for.
   * @param entryPointHint If present - a hint to what the entry point to this directory tree is.
   *     Will only be used if all of the following apply: (a) {@code
   *     destination.isDirectory()==true}, (b) there are 2 or more files in the {@code destination}
   *     directory, and (c) {@code entryPointHint.get()} is one of the files nested inside of the
   *     {@code destination} directory.
   */
  static FilesWithEntryPoint readFilesWithEntryPoint(
      File destination, Optional<File> entryPointHint, Path rootDir) {

    Path destinationPath = rootDir.relativize(toNormalizedPath(destination));

    if (destination.isFile()) {
      // The destination is a single file - find its root, and add this single file to the
      // FilesWithEntryPoint.
      return FilesWithEntryPoint.createSingleFile(
          destinationPath, toByteArraySupplier(destination));
    }

    if (!destination.isDirectory()) {
      // This isn't a file nor a directory - so it doesn't exist! Return empty FilesWithEntryPoint
      return FilesWithEntryPoint.create(ImmutableMap.of(), destinationPath);
    }

    // The destination is a directory - find all the actual files first
    ImmutableMap<Path, Supplier<byte[]>> files =
        Streams.stream(Files.fileTraverser().depthFirstPreOrder(destination))
            .filter(File::isFile)
            .collect(
                toImmutableMap(
                    file -> rootDir.relativize(toNormalizedPath(file)),
                    GcsPluginUtils::toByteArraySupplier));

    if (files.isEmpty()) {
      // The directory exists, but is empty. Return empty FilesWithEntryPoint
      return FilesWithEntryPoint.create(ImmutableMap.of(), destinationPath);
    }

    if (files.size() == 1) {
      // We got a directory, but it only has a single file. We can link to that.
      return FilesWithEntryPoint.create(files, getOnlyElement(files.keySet()));
    }

    // There are multiple files in the report! We need to check the entryPointHint
    Optional<Path> entryPointPath =
        entryPointHint.map(file -> rootDir.relativize(toNormalizedPath(file)));

    if (entryPointPath.isPresent() && files.containsKey(entryPointPath.get())) {
      // We were given the entry point! Use it!
      return FilesWithEntryPoint.create(files, entryPointPath.get());
    }

    // We weren't given an appropriate entry point. But we still need a single link to all this data
    // - so we'll zip it and just host a single file.
    Path zipFilePath = destinationPath.resolve(destinationPath.getFileName().toString() + ".zip");
    return FilesWithEntryPoint.createSingleFile(zipFilePath, createZippedByteArraySupplier(files));
  }

  static Supplier<byte[]> createZippedByteArraySupplier(Map<Path, Supplier<byte[]>> files) {
    return () -> zipFiles(files);
  }

  private static byte[] zipFiles(Map<Path, Supplier<byte[]>> files) {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(output)) {
      for (Path path : files.keySet()) {
        zip.putNextEntry(new ZipEntry(path.toString()));
        zip.write(files.get(path).get());
        zip.closeEntry();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return output.toByteArray();
  }

  private GcsPluginUtils() {}
}
