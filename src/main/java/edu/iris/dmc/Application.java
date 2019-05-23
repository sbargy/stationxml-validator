package edu.iris.dmc;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Unmarshaller;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;

import org.xml.sax.SAXException;

import edu.iris.dmc.fdsn.station.model.FDSNStationXML;
import edu.iris.dmc.seed.Blockette;
import edu.iris.dmc.seed.SeedException;
import edu.iris.dmc.seed.Volume;
import edu.iris.dmc.seed.blockette.util.BlocketteItrator;
import edu.iris.dmc.seed.builder.BlocketteBuilder;
import edu.iris.dmc.seed.director.BlocketteDirector;
import edu.iris.dmc.station.RuleEngineService;
import edu.iris.dmc.station.converter.SeedToXmlDocumentConverter;
import edu.iris.dmc.station.io.CsvPrintStream;
import edu.iris.dmc.station.io.HtmlPrintStream;
import edu.iris.dmc.station.io.ReportPrintStream;
import edu.iris.dmc.station.io.RuleResultPrintStream;
import edu.iris.dmc.station.io.XmlPrintStream;
import edu.iris.dmc.station.rules.Message;
import edu.iris.dmc.station.rules.Rule;
import edu.iris.dmc.station.rules.RuleContext;
import edu.iris.dmc.station.rules.UnitTable;

public class Application {
	private static final Logger LOGGER = Logger.getLogger(Application.class.getName());

	private static CommandLine commandLine;

	/**
	 * @param args
	 * @throws Exception
	 */
	public static void main(String[] args) throws Exception {
		try {
			commandLine = CommandLine.parse(args);
		} catch (CommandLineParseException e) {
			System.err.println(e.getMessage());
			System.exit(1);
		}
		Logger rootLogger = LogManager.getLogManager().getLogger("");
		rootLogger.setLevel(commandLine.getLogLevel());
		for (Handler h : rootLogger.getHandlers()) {
			h.setLevel(commandLine.getLogLevel());
		}
		LOGGER.info("STATION VALIDATOR");

		if (commandLine.showVersion()) {
			LOGGER.info(Application.getVersion());
			System.exit(0);
		} else if (commandLine.showHelp()) {
			help();
			System.exit(0);
		} else if (commandLine.showRules()) {
			printRules();
			System.exit(0);
		} else if (commandLine.showUnits()) {
			printUnits();
			System.exit(0);
		} else if (commandLine.file() == null) {
			LOGGER.info("File is required!");
			help();
			System.exit(1);
		}

		try {
			Application app = new Application();
			app.run();
		} catch (Exception e) {
			e.printStackTrace();
			LOGGER.log(Level.INFO, e.getMessage(), e);
			System.exit(1);
		}
	}

	public void run() throws Exception {

		Path path = commandLine.file();
		if (!path.toFile().exists()) {
			throw new IOException(String.format("File %s does not exist.  File is required!", path.toString()));
		}
		List<Path> input = new ArrayList<>();
		if (path.toFile().isDirectory()) {
			try (Stream<Path> paths = Files.walk(path)) {
				input = paths.filter(Files::isRegularFile).collect(Collectors.toList());
			}
		} else {
			input.add(path);
		}

		RuleContext rulesContext = RuleContext.of(commandLine.ignoreWarnings());
		if (commandLine.ignoreRules() != null) {
			for (int rule : commandLine.ignoreRules()) {
				rulesContext.ignoreRule(rule);
			}
		}
		File outputFile = null;
		if (commandLine.output() != null) {
			outputFile = commandLine.output().toFile();
			if (!outputFile.exists()) {
				throw new IOException(String.format("File %s is not found!", commandLine.output().toString()));
			}
		}
		try (OutputStream outputStream = (outputFile != null) ? new FileOutputStream(outputFile) : System.out;) {
			run(rulesContext, input, "csv", outputStream);
		}
	}

	private void run(RuleContext context, List<Path> input, String format, OutputStream outputStream) throws Exception {
		RuleEngineService ruleEngineService = new RuleEngineService(context.getIgnoreRules());
		try (final RuleResultPrintStream ps = getOutputStream(format, outputStream)) {
			for (Path p : input) {
				FDSNStationXML document = read(p);
				if (document == null) {
					continue;
				}
				ruleEngineService.executeAllRules(document, context, (RuleContext c, Message message) -> {
					message.setSource(p.toString());
					c.addViolation(message);
				});

				print(ps, context.map());
				context.clear();
			}
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

	private FDSNStationXML read(Path path) throws Exception {
		File file = path.toFile();
		if (!file.exists()) {
			LOGGER.severe("File does not exist.  File is required!");
			throw new IOException(String.format("File %s does not exist.  File is required!", file.getAbsoluteFile()));
		}
		try (InputStream is = new FileInputStream(file)) {
			if (file.getName().toLowerCase().endsWith(".xml")) {
				return (FDSNStationXML) DocumentMarshaller.unmarshal(is);
			} else {
				Volume volume = IrisUtil.readSeed(file);
				return SeedToXmlDocumentConverter.getInstance().convert(volume);
			}
		}
	}

	private void print(RuleResultPrintStream ps, Map<Integer, List<Message>> map) throws IOException {

		if (map != null && !map.isEmpty()) {
			SortedSet<Integer> keys = new TreeSet<>(map.keySet());
			for (Integer key : keys) {
				List<Message> l = map.get(key);
				for (Message m : l) {
					ps.print(m);
					ps.flush();
				}
			}
		} else {
			ps.printMessage("PASSED");
		}

		ps.printFooter();
	}

	public Volume load(File file) throws SeedException, IOException {
		try (final FileInputStream inputStream = new FileInputStream(file)) {
			return load(inputStream);
		}
	}

	public Volume load(InputStream inputStream) throws SeedException, IOException {
		BlocketteDirector director = new BlocketteDirector(new BlocketteBuilder());
		BlocketteItrator iterator = director.process(inputStream);

		Volume volume = new Volume();
		while (iterator.hasNext()) {
			Blockette blockette = iterator.next();
			volume.add(blockette);
		}
		return volume;
	}

	private RuleResultPrintStream getOutputStream(String format, OutputStream outputStream) throws IOException {
		if (format == null || format.isEmpty() || "html".equalsIgnoreCase(format)) {
			return new HtmlPrintStream(outputStream);
		} else if ("csv".equalsIgnoreCase(format)) {
			return new CsvPrintStream(outputStream);
		} else if ("xml".equalsIgnoreCase(format)) {
			return new XmlPrintStream(outputStream);
		} else if ("report".equalsIgnoreCase(format)) {
			return new ReportPrintStream(outputStream);
		} else {
			throw new IOException("Invalid format [" + format + "] requested");
		}
	}

	static boolean validateAgainstXSD(File xml) {
		try {

			SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
			Schema schema = factory.newSchema(new StreamSource("station.1.1.xsd"));

			Validator validator = schema.newValidator();
			validator.validate(new StreamSource(xml));
			return true;
		} catch (Exception exe) {
			exe.printStackTrace();
			return false;
		}
	}

	private static String getVersion() throws IOException {
		Properties prop = new Properties();
		InputStream in = Application.class.getClassLoader().getResourceAsStream("application.properties");
		prop.load(in);
		in.close();
		return prop.getProperty("application.version");
	}

	private static String center(String text, int length, String pad) {
		int width = length - text.length();
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < width / 2; i++) {
			builder.append(pad);
		}
		builder.append(text);
		int remainder = length - builder.length();
		for (int i = 0; i < remainder; i++) {
			builder.append(pad);
		}
		return builder.toString();
	}

	private static void printRules() {

		RuleEngineService ruleEngineService = new RuleEngineService(null);
		for (Rule rule : ruleEngineService.getRules()) {
			System.out.printf("%-8s %s%n", rule.getId(), rule.getDescription());
		}
	}

	private static void printUnits() {
		System.out.println("UNIT TABLE:");
		System.out.println("-------------------------------------");
		for (String unit : UnitTable.units) {
			System.out.println(unit);
		}

	}

	private static void help() throws IOException {
		String version = "Version " + getVersion();
		version = center(version, 62, " ");

		System.out.println("===============================================================");
		System.out.println("|" + center("FDSN StationXml validator", 62, " ") + "|");
		System.out.println("|" + version + "|");
		System.out.println("================================================================");
		System.out.println("Usage:");
		System.out.println("java -jar stationxml-validator <FILE> [OPTIONS]");
		System.out.println("OPTIONS");
		System.out.println("   --output      	: where to output result, default is System.out");
		System.out.println("   --ignore-warnings: don't show warnings");
		System.out.println("   --rules 			: print a list of validation rules");
		System.out.println("   --units 			: print a list of units used to validate");
		System.out.println("   --debug       	:");
		System.out.println("   --help        	: print this message");
		System.out.println("===============================================================");
		System.exit(0);
	}

}
