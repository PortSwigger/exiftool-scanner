package net.logicaltrust;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import burp.IExtensionHelpers;
import burp.IExtensionStateListener;
import burp.IResponseInfo;

public class ExifToolProcess implements IExtensionStateListener {
	
	private volatile Collection<String> typesToIgnore;
	private volatile Collection<String> linesToIgnore;
	
	private static final FileAttribute<Set<PosixFilePermission>> TEMP_FILE_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));
	private static final FileAttribute<Set<PosixFilePermission>> TEMP_DIR_PERMISSIONS = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

	private final PrintWriter writer;
	private final BufferedReader reader;
	private final IExtensionHelpers helpers;
	private final Path tempDirectory;
	private final SimpleLogger logger;
	private Process process;
	private Path extractedBinary;

	public ExifToolProcess(IExtensionHelpers helpers, SimpleLogger stdout) throws ExtensionInitException {
		this.helpers = helpers;
		this.logger = stdout;
		
		try {
			if (isWindows()) {
				tempDirectory = Files.createTempDirectory("burpexiftool");
				setWindowsPermissions(tempDirectory);
			} else {
				tempDirectory = Files.createTempDirectory("burpexiftool", TEMP_DIR_PERMISSIONS);
			}
			stdout.debugForce("Temp directory " + tempDirectory + " created");
		} catch (IOException e) {
			throw new ExtensionInitException("Cannot create temporary directory", e);
		}
		
		try {
			process = runProcess();
			writer = new PrintWriter(process.getOutputStream());
			reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
			stdout.debugForce("Process started");
		} catch (ExtensionInitException e) {
			deleteTempDir();
			throw e;
		}
	}
	
	public void setTypesToIgnore(Collection<String> typesToIgnore) {
		this.typesToIgnore = typesToIgnore;
	}
	
	public void setLinesToIgnore(Collection<String> linesToIgnore) {
		this.linesToIgnore = linesToIgnore.stream().map(line -> line + ":").collect(Collectors.toSet());
	}
	
	public List<String> readMetadataHtml(byte[] response) throws IOException {
		return readMetadata(response, "-m\n-S\n-E\n-sort\n");
	}
	
	public List<String> readMetadata(byte[] response) throws IOException {
		return readMetadata(response, "-m\n-S\n-sort\n");
	}
	
	public boolean canReadMetadata(byte[] response) {
		IResponseInfo responseInfo = helpers.analyzeResponse(response);
		return isMimeTypeAppropriate(responseInfo);
	}
	
	private List<String> readMetadata(byte[] response, String exifToolParams) throws IOException {
		logger.debug("Reading metadata from response");
		IResponseInfo responseInfo = helpers.analyzeResponse(response);
		if (!isMimeTypeAppropriate(responseInfo)) {
			logger.debug("Inappropriate MIME Type: " + responseInfo.getStatedMimeType() + ", " + responseInfo.getInferredMimeType());
			return Collections.emptyList();
		}
		
		Path tmp = writeToTempFile(responseInfo, response);
		List<String> result;
		synchronized (this) {
			notifyExifTool(tmp, exifToolParams);
			result = readResult();
		}
		logger.debug("Deleting temp file " + tmp);
		Files.deleteIfExists(tmp);
		
		return result;
	}
	
	private boolean isMimeTypeAppropriate(IResponseInfo responseInfo) {
		return !typesToIgnore.contains(responseInfo.getStatedMimeType()) && !typesToIgnore.contains(responseInfo.getInferredMimeType());
	}
	
	private Path createTempFile(String prefix, String suffix, FileAttribute<Set<PosixFilePermission>> permissions) throws IOException {
		Path tmp;
		if (isWindows()) {
			tmp = Files.createTempFile(tempDirectory, prefix, suffix);
			setWindowsPermissions(tmp);
		} else {
			 tmp = Files.createTempFile(tempDirectory, prefix, suffix, permissions);
		}
		return tmp;
	}
	
	private Path writeToTempFile(IResponseInfo responseInfo, byte[] response) throws IOException {
		logger.debug("Creating temp file");
		Path tmp = createTempFile("file", "", TEMP_FILE_PERMISSIONS);
		OutputStream tmpOs = Files.newOutputStream(tmp);
		tmpOs.write(response, responseInfo.getBodyOffset(), response.length - responseInfo.getBodyOffset());
		tmpOs.close();
		logger.debug("Temp file " + tmp + " created");
		return tmp;
	}
	
	private void notifyExifTool(Path tmp, String exifToolParams) {
		logger.debug("Notifying exiftool");
		writer.write(exifToolParams);
		writer.write(tmp.toString());
		writer.write("\n-execute\n");
		writer.flush();
		logger.debug("Exiftool notified");
	}
	
	private void exitExifTool() {
		logger.debugForce("Exit exiftool");
		writer.write("-stay_open\nFalse\n");
		writer.flush();
	}

	private List<String> readResult() throws IOException {
		logger.debug("Reading result from exiftool");
		List<String> result = new ArrayList<>();
		String line;
		while ((line = reader.readLine()) != null && logger.debug(line) && !("{ready}".equals(line) || "{ready-}".equals(line))) {
			if (isAppropriateLine(line)) {
				result.add(line);
			}
		}
		logger.debug(result.size() +  " elements read");
		return result;
	}

	private boolean isAppropriateLine(String line) {
		return linesToIgnore.stream().noneMatch((lineToIgnore) -> line.startsWith(lineToIgnore));
	}
	
	private boolean isWindows() {
		return System.getProperty("os.name").toLowerCase().contains("win");
	}
	
	private void setWindowsPermissions(Path path) {
		File file = path.toFile();
		file.setReadable(false, true);
		file.setWritable(true, true);
		file.setExecutable(false);
	}
	
	private Process runProcess() throws ExtensionInitException {
		try {
			Process process = new ProcessBuilder(prepareProcessParams("exiftool")).start();
			return process;
		} catch (IOException e) {
			logger.debugForce("'exiftool' not found in PATH.");
			if (isWindows()) {
				try {
					extractedBinary = extractBinary();
					logger.debugForce("Extracting exiftool to " + extractedBinary);
					Process process = new ProcessBuilder(prepareProcessParams(extractedBinary.toString())).start();
					return process;
				} catch (IOException e1) {
					throw new ExtensionInitException("Cannot run or extract embedded exiftool. Do you have 'exiftool' set in PATH?", e);
				} 
			} else {
				throw new ExtensionInitException("Cannot run. Do you have 'exiftool' set in PATH?", e);
			}
		}
	}
	
	private Path extractBinary() throws IOException  {
		InputStream resourceAsStream = getClass().getResourceAsStream("/exiftool.exe");
		Path exifToolBinary = createTempFile("exiftool", ".exe", null);
		FileOutputStream fos = null;
		try {
			fos = new FileOutputStream(exifToolBinary.toFile());
			byte[] buffer = new byte[32768];
			int read = 0;
			while ((read = resourceAsStream.read(buffer)) != -1) {
				fos.write(buffer, 0, read);
			}
			fos.flush();
		} finally {
			if (fos != null) {
				fos.close();
			}
		}
		
		return exifToolBinary;
	}
	
	private String[] prepareProcessParams(String executable) {
		return new String[] { executable, "-stay_open", "True", "-@", "-" };
	}
	
	private void deleteTempDir() {
		if (extractedBinary != null) {
			try {
				logger.debugForce("Deleting " + extractedBinary);
				Files.deleteIfExists(extractedBinary);
			} catch (IOException e) {
				e.printStackTrace(logger.getStderr());
			}
		}
		try {
			logger.debugForce("Deleting " + tempDirectory);
			Files.deleteIfExists(tempDirectory);
		} catch (IOException e) {
			e.printStackTrace(logger.getStderr());
		}
	}
	
	@Override
	public void extensionUnloaded() {
		exitExifTool();
		
		try {
			Thread.sleep(5000);
		} catch (InterruptedException e1) {
			e1.printStackTrace();
		}
		
		try {
			process.waitFor(30, TimeUnit.SECONDS);
			deleteTempDir();
		} catch (InterruptedException e1) {
			e1.printStackTrace(logger.getStderr());
		}
	}
	
}
