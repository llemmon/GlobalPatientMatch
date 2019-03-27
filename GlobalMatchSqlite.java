package externalSortPackage;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;

public class GlobalMatchSqlite {

	private static Connection db;

	private static String dbDirectory;
	private static String dbName;
	private static ResultSet resultSet = null;

	private final static DateTimeFormatter dateTimeformat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	//private final static DateTimeFormatter dateTimeHL7format = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private final static String directorySeparator = "/";
	private final static String delimPipe = "|";
	private final static String delimComma = ",";

	private static int siteidIdx = 0;				// index into columns in input hash files
	private static int projectidIdx = 1;
	private static int pidhashIdx = 2;
	private static int hash1Idx = 3;
	private static int hash2Idx = 4;
	private static int hash3Idx = 5;
	private static int hash4Idx = 6;
	private static int hash5Idx = 7;
	private static int hash6Idx = 8;
	private static int hash7Idx = 9;
	private static int hash8Idx = 10;
	private static int hash9Idx = 11;
	private static int hash10Idx = 12;
	private static int exceptFlagIdx = 13;
	private static int recordsToSkip = 1;

	private static AtomicInteger globalId;
	private static int atomicIntegerSeed;
	private static String atomicIntegerPath;
	private static final String atomicIntegerFile = "global-match-globalId.txt";
	private static Integer patAliasGlobalIdCutoff;

	private static String configRootPath;
	private static String configFilePath;
	private static final String configFileName = "global-match.properties";
	private static String inputDir;
	private static String outputDir;
	private static String processedDir;
	private static String logFile;
	private static String logFileDir;
	private static final String logFileName = "match-log-";
	//private static String outputFile1;
	private static String inputFileNamePrefix;
	private static String inputFileNameSuffix;
	private static String tempMessage;
	private static final String TempTableIdKey = "TempTableId";
	private static final String TempTableTextKey = "TempTableText";

	private static Integer patGlobalIdCount = 0;
	private static Map<Integer, List<Integer>> patGlobalIdMap = new HashMap<Integer, List<Integer>>(1000);
	//private static HashSet<String> matchSet = new HashSet<String>(100);  // init capacity (def 16), load factor
	private static List<Integer> matchSequence = new ArrayList<Integer>();

	protected final static Map<Integer, String> matchRule = new HashMap<Integer, String>();
	static {
		matchRule.put(0, "1,2,3,4,5,6,7,8,9,10 matchto 1,2,3,4,5,6,7,8,9,10");
		matchRule.put(1, "1,2,5,9,10 matchto 1,2,5,9,10");
		matchRule.put(2, "3,4,6 matchto 3,4,6");
		matchRule.put(3, "1 matchto 1");
		matchRule.put(4, "1 matchto 2");
		matchRule.put(5, "1 matchto 5");
		matchRule.put(6, "1 matchto 9");
		matchRule.put(7, "1 matchto 10");
		matchRule.put(8, "3 matchto 3");
		matchRule.put(9, "3 matchto 4");
		matchRule.put(10, "3 matchto 6");
		matchRule.put(11, "7 matchto 7");
		matchRule.put(12, "8 matchto 8");
	}

	private static String changeDirectorySeparator(String filePath) {
		return filePath.replaceAll("\\\\", directorySeparator);	// change dir separator if Windows
	}
	public static String timeNowFormatted() {
		LocalDateTime now = LocalDateTime.now();		//Get current date time
		return now.format( dateTimeformat );
	}
	public static String dateNowFormatted() {
		LocalDate now = LocalDate.now();				//Get current date
		return now.format(DateTimeFormatter.ISO_DATE);
	}
	/**
	 * Connect to SQLite database
	 */
	public static void connectDb() {
		db = null;
		try {
			// db parameters
			String url = "jdbc:sqlite:" + dbDirectory + dbName;		// create url to database
			System.out.println("connecting to url: " + url);
			db = DriverManager.getConnection(url);					// create connection to database
			System.out.println("Connection to SQLite has been established.");
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		} finally {
		}
	}

	private static void processInputFiles(int step) {

		String fullName = null;			// read hash files from input directory
		tempMessage = "step " + step + ": reading input files from: " + inputDir;
		System.out.println( tempMessage );
		writeLog( tempMessage );

		ArrayList<String> inputFiles = getFileNames1Dir(inputDir, inputFileNamePrefix, inputFileNameSuffix);	// go read files in directory
		if (inputFiles == null || inputFiles.size() == 0) {
			tempMessage = "***no input files found in directory " + inputDir;
			System.out.println( tempMessage );
			writeLog( tempMessage );
			return;
		}
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}

		for (int i=0; i< inputFiles.size(); i++) {

			fullName = inputFiles.get(i);
			fullName = changeDirectorySeparator(fullName);	// change file separator if Windows
			//fileName = getFileName(fullName); 			// get file name without extension
			String inputFile1 = fullName;
			String invalidDataFile = changeFileExtension(inputFile1, "bad");
			int recordsRead = 0;

			// read input file
			tempMessage = "processing hash file: " + inputFile1;
			System.out.println( tempMessage );
			writeLog( tempMessage );

			File fileIn = new File(inputFile1);
			try ( BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(fileIn), "UTF-8"))) {

				String line = null;
				while ((line = br.readLine()) != null) {
					recordsRead = recordsRead + 1;
					if (recordsRead <= recordsToSkip) {
						System.out.println("skipping record " + recordsRead);
						continue;
					}

					String lineIn = line.trim().replaceAll("\\s+", " ");	// reduce multiple spaces to single space
					String[] splitLine = lineIn.split(delimComma);			// split incoming text

					// check that primary data is valid
					boolean validData = true;
					if (splitLine[siteidIdx] == null || splitLine[siteidIdx].isEmpty()) {
						validData = false;
					}
					if (splitLine[exceptFlagIdx] == null || splitLine[exceptFlagIdx].isEmpty()) {
						validData = false;
					}
					if (splitLine[hash1Idx] == null || splitLine[hash1Idx].isEmpty() || splitLine[hash1Idx].equalsIgnoreCase("NULL")) {
						validData = false;
					}
					if (splitLine[hash2Idx] == null || splitLine[hash2Idx].isEmpty() || splitLine[hash2Idx].equalsIgnoreCase("NULL")) {
						validData = false;
					}
					if (splitLine[hash5Idx] == null || splitLine[hash5Idx].isEmpty() || splitLine[hash5Idx].equalsIgnoreCase("NULL")) {
						validData = false;
					}
					if (splitLine[hash9Idx] == null || splitLine[hash9Idx].isEmpty() || splitLine[hash9Idx].equalsIgnoreCase("NULL")) {
						validData = false;
					}
					if (splitLine[hash10Idx] == null || splitLine[hash10Idx].isEmpty() || splitLine[hash10Idx].equalsIgnoreCase("NULL")) {
						validData = false;
					}
					if (!validData) {
						writeInvalidData( invalidDataFile, lineIn );	// write out data for later
						continue;			// skip to next record
					}

					String sql = "INSERT INTO GlobalMatch ("
							+"siteId,projectId,pidhash,hash1,hash2,hash3,hash4,hash5,hash6,hash7,hash8,hash9,hash10,exception,globalId) "
							+ "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

					try ( PreparedStatement pstmt = db.prepareStatement(sql)) {
						pstmt.setString(1, splitLine[siteidIdx]);
						pstmt.setString(2, splitLine[projectidIdx]);
						pstmt.setString(3, splitLine[pidhashIdx]);
						pstmt.setString(4, splitLine[hash1Idx]);
						pstmt.setString(5, splitLine[hash2Idx]);
						pstmt.setString(6, splitLine[hash3Idx]);
						pstmt.setString(7, splitLine[hash4Idx]);
						pstmt.setString(8, splitLine[hash5Idx]);
						pstmt.setString(9, splitLine[hash6Idx]);
						pstmt.setString(10, splitLine[hash7Idx]);
						pstmt.setString(11, splitLine[hash8Idx]);
						pstmt.setString(12, splitLine[hash9Idx]);
						pstmt.setString(13, splitLine[hash10Idx]);
						pstmt.setString(14, splitLine[exceptFlagIdx]);
						pstmt.setString(15, "0");

						pstmt.executeUpdate();			// store to database
						System.out.println("stored to database: " + splitLine[hash1Idx]);
					} catch (SQLException e) {
						System.out.println(e.getMessage());
					}
				}
			} catch (UnsupportedEncodingException e2) {
				e2.printStackTrace();
			} catch (FileNotFoundException e2) {
				e2.printStackTrace();
			} catch (IOException e2) {
				e2.printStackTrace();
			}

			tempMessage = "read " + recordsRead + " lines from: " + inputFile1;
			System.out.println( tempMessage );
			writeLog( tempMessage );
			// end read input file

			// move file to processed dir
			File fileToMove = FileUtils.getFile(fullName);
			if (fileToMove.exists()) {
				boolean fileMoved = false;
				try {
					fileMoved = moveFileToDir(processedDir, fullName);
				} catch (IOException e) {
					e.printStackTrace();
				}
				if (!fileMoved) {
					tempMessage = "***file not moved to processed dir: " + fullName;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
		}
	}

	// step 2 run match rules as indicated
	private static void runMatchRules(int step) {

		for (Integer currentRule : matchSequence) {		// parse and run match rules

			String ruleTextFull = matchRule.get( currentRule );
			int matchToIndex = ruleTextFull.indexOf(" matchto ");
			String key1Part = ruleTextFull.substring(0, matchToIndex);
			String key2Part = ruleTextFull.substring(matchToIndex + 9);
			boolean keySame = false;								// indicates if key1 = key2
			List<Integer> key1Index = new ArrayList<Integer>();		// holds index to columns in key1
			List<Integer> key2Index = new ArrayList<Integer>();		// holds index to columns in key2
			String[] key1Text = key1Part.split(delimComma);			// split key1 text
			for (int i = 0; i < key1Text.length; i++) {	
				key1Index.add( Integer.parseInt( key1Text[i]) );	// save key1 indexes in list
			}
			if (key1Part.equals(key2Part)) {
				key2Index = key1Index.stream()
						.collect(Collectors.toList());		// if key1 = key2, just copy key1 - Java 8							
				keySame = true;
			} else {													// else parse key2
				String[] key2Text = key2Part.split(delimComma);			// split key2 text
				for (int i = 0; i < key2Text.length; i++) {	
					key2Index.add( Integer.parseInt( key2Text[i]) );	// save key2 indexes in list
				}
			}
			
			assignPatientAliasGlobalIds();		// assign Global Ids to patient aliases first

			tempMessage = "Processing Match Rule " + currentRule;
			System.out.println( tempMessage );
			writeLog( tempMessage );

			keySame = true;
			switch (currentRule) {		
			case 0 :
				keySame = true;
				readGlobalMatchRule0( keySame );		// go run match rule indicated
				break;
			case 1 :
				keySame = true;
				readGlobalMatchRule1( keySame );
				break;
			case 2 :
				keySame = true;
				readGlobalMatchRule2( keySame );
				break;
			case 3 :
				keySame = true;
				readGlobalMatchRule3( keySame );
				break;
			case 4 :
				keySame = false;
				readGlobalMatchRule4( keySame );
				break;
			case 5 :
				keySame = false;
				readGlobalMatchRule5( keySame );
				break;
			case 6 :
				keySame = false;
				readGlobalMatchRule6( keySame );
				break;
			case 7 :
				keySame = false;
				readGlobalMatchRule7( keySame );
				break;
			case 8 :
				keySame = true;
				readGlobalMatchRule8( keySame );
				break;
			case 9 :
				keySame = false;
				readGlobalMatchRule9( keySame );
				break;
			case 10 :
				keySame = false;
				readGlobalMatchRule10( keySame );
				break;
			case 11 :
				keySame = true;
				readGlobalMatchRule11( keySame );
				break;
			case 12 :
				keySame = true;
				readGlobalMatchRule12( keySame );
				break;
			default :
			}

			System.out.println("finished Match Rule " + currentRule);
		}

		assignMatchingGlobalIds();	// go assign global ids for matched patients
		assignUnMatchedGlobalIds();	// go assign global ids for patients without global ids
	}

	private static void assignMatchingGlobalIds() {
		tempMessage = "-----Assigning global ids to matching patients";
		System.out.println( tempMessage );
		writeLog( tempMessage );
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		for (Map.Entry<Integer, List<Integer>> entry : patGlobalIdMap.entrySet()) {

			Integer patGlobalIdMapEntry = entry.getKey();
			List<Integer> patList = entry.getValue();
			System.out.println("map entry: " + patGlobalIdMapEntry + "  pat list: " + patList);

			int patCount = 0;
			StringBuilder sb1 = new StringBuilder();
			for (Integer num : patList) {
				patCount++;
				if (patCount == 1) {
					sb1.append(num.toString());
				} else {
					sb1.append(delimComma + num.toString());
				}
			}
			System.out.println("ids: " + sb1);
			Integer maxGlobalId = 0;
			String sql1 = "SELECT globalId FROM GlobalMatch WHERE id IN (" + sb1.toString() + ")";
			try ( PreparedStatement pstmt1 = db.prepareStatement(sql1)) {	
				pstmt1.setFetchSize(100);				//number of rows to be fetched when needed
				resultSet = pstmt1.executeQuery();
				while (resultSet.next()) {				// get max global Id from this group

					Integer currGlobalId = resultSet.getInt("globalId");
					if (currGlobalId > 0) {
						if (currGlobalId < patAliasGlobalIdCutoff) {
							maxGlobalId = currGlobalId;		// if alias patient global id use that for all
							break;
						} else if (maxGlobalId < currGlobalId) {
							maxGlobalId = currGlobalId;
						}
					}
					//System.out.println("gblid: " + currGlobalId + "  max: " + maxGlobalId);
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e) {
				System.out.println(e.getMessage());
			}

			if (maxGlobalId == 0) {
				maxGlobalId = globalId.incrementAndGet();	// no global id found, get next global Id
			}
			for (Integer num : patList) {

				String sql2 = "UPDATE GlobalMatch SET globalId = ? WHERE id = ?";
				try ( PreparedStatement pstmt2 = db.prepareStatement(sql2)) {
					pstmt2.setInt(1, maxGlobalId);		// set the corresponding param
					pstmt2.setInt(2, num);
					pstmt2.executeUpdate();				// update this record 
				} catch (SQLException e) {
					System.out.println(e.getMessage());
				}
			}
		}

		/*
		try ( Statement pstmt1 = db.createStatement(
              ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {	// if want to update during loop, not in sqlite
				//db.prepareStatement(sql1);	
				pstmt1.setFetchSize(100);				//number of rows to be fetched when needed
				resultSet = pstmt1.executeQuery(sql1);
		 */
	}

	private static void assignUnMatchedGlobalIds() {
		tempMessage = "-----Assigning global ids to unmatched patients";
		System.out.println( tempMessage );
		writeLog( tempMessage );
		int unAssignedGlobalIds = 0;

		createTempTableIdKey();		// go create temp table to hold pats without globalId

		String sql3 = "SELECT id FROM GlobalMatch WHERE globalId = 0";	// get pats without globalId
		try ( PreparedStatement pstmt1 = db.prepareStatement(sql3)) {	
			pstmt1.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt1.executeQuery();
			while (resultSet.next()) {				// get row ids of unassigned patients, save to temp 
				Integer rowId = resultSet.getInt("id");
				Integer nextGlobalId = globalId.incrementAndGet();	// assign next global id
				storeToTempTableIdKey(rowId, nextGlobalId);			// save to temp file
				unAssignedGlobalIds++;
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
		tempMessage = "Assigned " + unAssignedGlobalIds + " global ids to patients without global ids";
		System.out.println( tempMessage );
		writeLog( tempMessage );

		// update GlobalMatch.globalId using rowId, globalId from TempTable
		String sql2 = "UPDATE GlobalMatch SET globalId ="
				+ " (SELECT " + TempTableIdKey + ".gblId FROM " + TempTableIdKey
				+ " WHERE GlobalMatch.id = " + TempTableIdKey + ".rowId)"
				+ " WHERE GlobalMatch.globalId = 0";

		try ( PreparedStatement pstmt2 = db.prepareStatement(sql2)) {
			pstmt2.executeUpdate();				// update GlobalMatch record from temp table 
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}
	
	private static void assignPatientAliasGlobalIds() {
		
		Map<String, Integer> patAliasMap = new HashMap<String, Integer>(1000);
		
		String lastPat = "xx";
		int recordsRead = 0;
		int patAliasMapCount = 0;
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		tempMessage = "-----patient alias match starting";
		System.out.println( tempMessage );
		writeLog( tempMessage );
		
		// query database
		String sqlQuery = "SELECT pidhash,id FROM GlobalMatch INDEXED BY pidindex";	// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery
			while (resultSet.next()) {

				recordsRead++;
				String currentPat = resultSet.getString("pidhash");		// get pid from this resultset row
				System.out.println("last: " + lastPat + "  curr: " + currentPat);
				
				if (currentPat.equalsIgnoreCase(lastPat)) {			//if key is =, means patients match
					patAliasMapCount++;
					patAliasMap.merge(currentPat, 1, Integer::sum);	// add 1 to this pat, stores if not there

					if ((recordsRead % 100000) == 0) {
						tempMessage = "records processed: " +recordsRead+ "  pat alias matches found: " +patAliasMapCount;
						System.out.println( tempMessage );
						writeLog( tempMessage );				// write out patient match keys so far
					}
				}
				lastPat = currentPat;		// save current patient key for next loop
			}

			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
		tempMessage = "records processed: " +recordsRead+ "  pat alias matches found: " +patAliasMapCount;
		System.out.println( tempMessage );
		writeLog( tempMessage );				// write out patient match keys
		
		Integer patAliasGlobalId = 100;		// start pat alias global ids with low number, not use regular global id
		
		// loop thru patAliasMap entries to assign global ids
		for (String patPidKey : patAliasMap.keySet()) {	// only need map key not value

			//Integer nextGlobalId = globalId.incrementAndGet();	// get next global id
			patAliasGlobalId++;
			
			String sql2 = "UPDATE GlobalMatch INDEXED BY pidindex SET globalId = ? WHERE pidhash = ?";
			try ( PreparedStatement pstmt2 = db.prepareStatement(sql2)) {
				pstmt2.setInt(1, patAliasGlobalId);		// set the corresponding params
				pstmt2.setString(2, patPidKey);
				pstmt2.executeUpdate();				// update this record 
			} catch (SQLException e) {
				System.out.println(e.getMessage());
			}
		}
		
		patAliasGlobalIdCutoff = ((patAliasGlobalId + 100 - 1) /100) * 100;		// round up to nearest 100
		globalId = new AtomicInteger(patAliasGlobalIdCutoff);	// set regular global id above alias global id
		
		tempMessage = "setting Global Id seed to: " +patAliasGlobalIdCutoff+ " after assigning pat alias global ids";
		System.out.println( tempMessage );
		writeLog( tempMessage );				// write out 
		
	}

	private static void readGlobalMatchRule0(boolean keySame) {
		System.out.println("processing Match Rule0");

		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash1,hash2,hash3,hash4,hash5,hash6,hash7,hash8,hash9,hash10,id "  // must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match0";					// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 0 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void addToGlobalIdGroup(ArrayList<Integer> globalIdGroup) {
		List<Integer> copyList = globalIdGroup.stream()
				.collect(Collectors.toList());		// copy list so is independent list - Java 8
		patGlobalIdCount++;
		patGlobalIdMap.put(patGlobalIdCount, copyList);		// save to map of matched patients
		patGlobalIdMap.forEach((k, v) -> System.out.println((k + ":" + v)));	// print out - Java 8

	}

	private static void readGlobalMatchRule1(boolean keySame) {
		System.out.println("processing Match Rule1");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash1,hash2,hash5,hash9,hash10,id "  // must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match1";					// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 1 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void readGlobalMatchRule2(boolean keySame) {
		System.out.println("processing Match Rule2");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash3,hash4,hash6,id "		// must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match2";			// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {
				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 2 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void readGlobalMatchRule3(boolean keySame) {
		System.out.println("processing Match Rule 3");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash1,id "  				// must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match3";		// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 3 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void readGlobalMatchRule4(boolean keySame) {
		System.out.println("processing Match Rule4");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare

		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash2, 2nd to check if hash1=hash2
			// query database
			String sqlQuery = "SELECT hash1,hash2,id "  		// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match4";		// create prepared statement
			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
				while (resultSet.next()) {
					String hash1 = resultSet.getString("hash1");		// get data from this resultset row
					String hash2 = resultSet.getString("hash2");
					int rowId = resultSet.getInt("id");
					System.out.println("h1: "+hash1+" h2: "+hash2+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash2, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash1+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2 matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}
		}
	}

	private static void readGlobalMatchRule5(boolean keySame) {
		System.out.println("processing Match Rule5");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare

		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash5, 2nd to check if hash1=hash5
			// query database
			String sqlQuery = "SELECT hash1,hash5,id "  		// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match5";		// create prepared statement
			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
				while (resultSet.next()) {
					String hash1 = resultSet.getString("hash1");		// get data from this resultset row
					String hash5 = resultSet.getString("hash5");
					int rowId = resultSet.getInt("id");
					System.out.println("h1: "+hash1+" h5: "+hash5+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash5, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash1+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2 matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}	
		}
	}

	private static void readGlobalMatchRule6(boolean keySame) {
		System.out.println("processing Match Rule6");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare

		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash9, 2nd to check if hash1=hash9
			// query database
			String sqlQuery = "SELECT hash1,hash9,id "  		// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match6";		// create prepared statement
			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery
				while (resultSet.next()) {
					String hash1 = resultSet.getString("hash1");		// get data from this resultset row
					String hash9 = resultSet.getString("hash9");
					int rowId = resultSet.getInt("id");
					System.out.println("h1: "+hash1+" h9: "+hash9+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash9, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash1+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2  matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}
		}	
	}

	private static void readGlobalMatchRule7(boolean keySame) {
		System.out.println("processing Match Rule7");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare
		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash2, 2nd to check if hash1=hash2
			// query database
			String sqlQuery = "SELECT hash1,hash10,id "  		// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match7";		// create prepared statement
			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
				while (resultSet.next()) {
					String hash1 = resultSet.getString("hash1");		// get data from this resultset row
					String hash10 = resultSet.getString("hash10");
					int rowId = resultSet.getInt("id");
					System.out.println("h1: "+hash1+" h10: "+hash10+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash10, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash1+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2  matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}
		}
	}

	private static void readGlobalMatchRule8(boolean keySame) {
		System.out.println("processing Match Rule 8");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash3,id "  				// must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match8";		// create prepared statement

		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 8 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void readGlobalMatchRule9(boolean keySame) {
		System.out.println("processing Match Rule9");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare

		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash4, 2nd to check if hash3=hash4
			// query database
			String sqlQuery = "SELECT hash3,hash4,id "  		// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match9";		// create prepared statement

			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
				while (resultSet.next()) {
					String hash3 = resultSet.getString("hash3");		// get data from this resultset row
					String hash4 = resultSet.getString("hash4");
					int rowId = resultSet.getInt("id");
					System.out.println("h3: "+hash3+" h4: "+hash4+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash4, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash3+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2 matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}
		}
	}

	private static void readGlobalMatchRule10(boolean keySame) {
		System.out.println("processing Match Rule10");
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		createTempTableTextKey();		// go create temp table to hold pats to compare
		for (int loop = 1; loop < 3; loop++) {	// loop 2 times, 1st to store hash2, 2nd to check if hash1=hash2
			// query database
			String sqlQuery = "SELECT hash3,hash6,id "  			// must have all fields in index 
					+ "FROM GlobalMatch INDEXED BY match10";		// create prepared statement
			try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
				pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
				resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
				while (resultSet.next()) {
					String hash3 = resultSet.getString("hash3");		// get data from this resultset row
					String hash6 = resultSet.getString("hash6");
					int rowId = resultSet.getInt("id");
					System.out.println("h3: "+hash3+" h6: "+hash6+" id: "+rowId);
					if (loop == 1) {
						storeToTempTableTextKey(hash6, rowId);	// save to temp file 1st time through
					} else {									// 2nd time through check if find match
						String sql9 = "SELECT name,rowId FROM " +TempTableTextKey+ " INDEXED BY index1 WHERE name = '"+hash3+"'";
						try ( PreparedStatement pstmt9 = db.prepareStatement(sql9)) { 
							ResultSet rset9 = pstmt9.executeQuery();
							while (rset9.next()) {
								String tempName = rset9.getString("name");		// get data from this resultset row
								int tempRowId = rset9.getInt("rowId");
								System.out.println("found in temp: " + tempName + " " + tempRowId);
								if (rowId != tempRowId) {
									currGlobalIdGroup.add(rowId);			// save ids of these 2 matching patients
									currGlobalIdGroup.add(tempRowId);									
								}
							}
							if (currGlobalIdGroup !=null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
								addToGlobalIdGroup( currGlobalIdGroup );
								currGlobalIdGroup.clear();  // clear list for next match
							}
							if (rset9 != null) { rset9.close(); }
						} 
					}
				}
				if (resultSet != null) {
					resultSet.close();
				}
			} catch (SQLException e1) {
				e1.printStackTrace();
			} catch (Exception e1) {
				e1.printStackTrace();	
			}
		}	

	}

	private static void readGlobalMatchRule11(boolean keySame) {
		System.out.println("processing Match Rule 11");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash7,id "  					// must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match11";		// create prepared statement

		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 11 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	private static void readGlobalMatchRule12(boolean keySame) {
		System.out.println("processing Match Rule 12");
		int recordsRead = 0;
		int patientMatches = 0;
		String lastPat = "xx";
		Integer currRowId = 0;
		Integer lastRowId = 0;
		boolean currMatch = false;
		boolean lastMatch = false;
		ArrayList<Integer> currGlobalIdGroup = new ArrayList<Integer>();

		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		// query database
		String sqlQuery = "SELECT hash8,id "  					// must have all fields in index 
				+ "FROM GlobalMatch INDEXED BY match12";		// create prepared statement
		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)
			int columnCount = resultSet.getMetaData().getColumnCount();		// get column count
			while (resultSet.next()) {

				recordsRead++;
				StringBuilder sb1 = new StringBuilder();
				for (int k = 1; k < columnCount; k++) {		//make up key to compare, all but last column
					sb1.append(resultSet.getString(k) + delimPipe);
				}
				currRowId = resultSet.getInt("id");		// get row id in last column
				String currPat = sb1.toString();
				System.out.println("row: " + currRowId + " currPat: " + sb1);

				if (currPat.equalsIgnoreCase(lastPat)) {
					currMatch = true;
					patientMatches++;
					if (!lastMatch) {
						currGlobalIdGroup.add(lastRowId);	//if match and not last match add both to set
						currGlobalIdGroup.add(currRowId);
						//matchSet.add(currPat);						
					} else {
						currGlobalIdGroup.add(currRowId);	// else matched before so lastPat already included
					}
				} else {
					currMatch = false;
					if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
						addToGlobalIdGroup( currGlobalIdGroup );
						currGlobalIdGroup.clear();					// clear list for next match
					}
				}
				lastPat = currPat;		// save current patient key for next loop
				lastMatch = currMatch;
				lastRowId = currRowId;

				if ((recordsRead % 100000) == 0) {
					// write out patient match keys so far
					tempMessage = "Match Rule 12 records processed: " + recordsRead + "  matches found: " + patientMatches;
					System.out.println( tempMessage );
					writeLog( tempMessage );
				}
			}
			if (currGlobalIdGroup != null && !currGlobalIdGroup.isEmpty()) {	// if have entry, then save
				addToGlobalIdGroup( currGlobalIdGroup );
				currGlobalIdGroup.clear();					// clear list for next match
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}

	/*
	private static void readGlobalMatch() {
		int recordsRead = 0;
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}

		// check if table exists
		//String query1 = "SELECT name FROM sqlite_temp_master WHERE type='table' AND name='tempMatch'";
		String query1 = "SELECT name FROM sqlite_master WHERE type='table' AND name='GlobalMatch'";
		try ( PreparedStatement pstmt1 = db.prepareStatement(query1)) {
			resultSet = pstmt1.executeQuery();		// executeQuery
			if (resultSet.next() == false) {	// check if resultSet is empty
				// message should always be found in table since put there by initial send
				System.out.println("***existing table not found");
			} else {
				do {
					String fileName = resultSet.getString("name");
					System.out.println("found file name: " + fileName);
				} while (resultSet.next()); 
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		}

		// query database
		//db.setTransactionIsolation(TRANSACTION_READ_COMMITTED);
		//String sqlQuery = "SELECT LNAME,FNAME,GENDER FROM patient ORDER BY FNAME,GENDER LIMIT 10 OFFSET 20";  // offset jumps to that row
		String sqlQuery = "SELECT siteId,projectId,pidhash,hash1,hash2,hash3,hash4,hash5,hash6,hash7,hash8,hash9,hash10,exception,globalId "
				+ " FROM GlobalMatch";		// USE INDEX(match1)	// create prepared statement

		try ( PreparedStatement pstmt = db.prepareStatement(sqlQuery)) {
			//ResultSet.TYPE_SCROLL_SENSITIVE, ResultSet.CONCUR_UPDATABLE);	// if want to update during resultSet	
			pstmt.setFetchSize(1000);				//number of rows to be fetched when needed
			resultSet = pstmt.executeQuery();		// executeQuery (executeUpdate won't return result set)

			while (resultSet.next()) {

				recordsRead++;
				String siteId = resultSet.getString("siteId");		// get data from this resultset row
				String projectId = resultSet.getString("projectId");
				String pidhash = resultSet.getString("pidhash");
				String hash1 = resultSet.getString("hash1");
				String hash2 = resultSet.getString("hash2");
				String hash3 = resultSet.getString("hash3");
				String hash4 = resultSet.getString("hash4");
				String hash5 = resultSet.getString("hash5");
				String hash6 = resultSet.getString("hash6");
				String hash7 = resultSet.getString("hash7");
				String hash8 = resultSet.getString("hash8");
				String hash9 = resultSet.getString("hash9");
				String hash10 = resultSet.getString("hash10");
				String exception = resultSet.getString("exception");
				String gblId = resultSet.getString("globalId");

				System.out.println("siteId: "+siteId+" projId: "+projectId+" pid: "+pidhash);
				System.out.println("h1: "+hash1+" h2: "+hash2+" h3: "+hash3+" h4: "+hash4+" h5: "+hash5);
				System.out.println("h6: "+hash6+" h7: "+hash7+" h8: "+hash8+" h9: "+hash9+" h10: "+hash10);
				System.out.println("except: "+exception+"  globalId: "+gblId);
			}
			if (resultSet != null) {
				resultSet.close();
			}
		} catch (SQLException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();	
		}
	}
	*/

	public static void writeInvalidData( String fileOut, String invalidData) {
		if (invalidData == null || invalidData.isEmpty()) {		// check if anything in invalidData
			return;
		}
		System.out.println("skip invalid data record");

		Path path = Paths.get( fileOut );
		if (Files.notExists(path)) {
			try {
				Files.createFile(path);		// create file if doesn't exist
			} catch (IOException e) { e.printStackTrace(); }
		}
		try(    FileWriter fw = new FileWriter( fileOut, true);  //try-with-resources --> autoclose, true=append
				BufferedWriter bw = new BufferedWriter(fw);
				PrintWriter out = new PrintWriter(bw)) {

			out.println( invalidData );				// write out data
		}  
		catch( IOException e ){
			// File writing/opening failed at some stage.
			System.out.println("**Unable to write to file: " + fileOut);
		}
	}

	public static void writeLog(String textMessage) {

		String logFile1 = logFile + dateNowFormatted() + ".txt";
		String text = timeNowFormatted() + " " + textMessage;

		Path path = Paths.get( logFile1 );
		if (Files.notExists(path)) {
			try { Files.createFile(path);		// create file if doesn't exist
			} catch (IOException e) { e.printStackTrace(); }
		}
		try(    FileWriter fw = new FileWriter( logFile1, true);  //try-with-resources --> autoclose
				BufferedWriter bw = new BufferedWriter(fw);
				PrintWriter out = new PrintWriter(bw)) {
			out.println( text );		// write out text
		}  
		catch( IOException e ){
			// File writing/opening failed at some stage.
			System.out.println("**Unable to write to log file " + logFile1);
		}
	}

	private static String makeFilePath(String filePath, String fileName) {
		filePath = changeDirectorySeparator(filePath);
		if (filePath.endsWith(directorySeparator)) {
			return filePath + fileName;
		} else {
			return filePath + directorySeparator + fileName;
		}
	}

	public static String changeFileExtension(String fileToRename, String newExtension) {
		int index = fileToRename.lastIndexOf('.');
		String name = fileToRename.substring(0, index + 1);
		return name + newExtension;
	}

	/*
	private static String getFileDir(String fullFilePath) {
		String fullName = changeDirectorySeparator(fullFilePath);	// check if Windows separator
		String fileDir = fullName.substring(0, fullName.lastIndexOf("/")+1);
		return fileDir;
	}

	private static String getFileName(String fullFilePath) {
		String fileName = null;
		String fullName = changeDirectorySeparator(fullFilePath);	// check if Windows separator
		int dotIndex = fullName.lastIndexOf(".");					// see if file type present
		if (dotIndex >= 0) {
			fileName = fullName.substring(fullName.lastIndexOf("/")+1, dotIndex);
		} else {
			fileName = fullName.substring(fullName.lastIndexOf("/")+1);
		}
		return fileName;
	}
	*/

	private static String getFileExtension(String fullFilePath) {
		String fullName = changeDirectorySeparator(fullFilePath);	// check if Windows separator
		String fileName = fullName.substring(fullName.lastIndexOf("/")+1);
		return fileName;
	}

	public static boolean moveFileToDir(String processedFilesDirectory, String fileToMoveName) throws IOException {
		boolean success = false;
		String tempMessage = null;
		String fileToMoveNameOnly = null;
		String fileNewLocName = null;

		fileToMoveName = changeDirectorySeparator(fileToMoveName);
		fileToMoveNameOnly = getFileExtension(fileToMoveName);
		fileNewLocName = makeFilePath(processedFilesDirectory, fileToMoveNameOnly);	// indicate new location	
		File fileToMove = FileUtils.getFile(fileToMoveName);
		File fileNewLoc = FileUtils.getFile(fileNewLocName);
		if (!fileToMove.exists()) {
			tempMessage = "***file to move not found: " + fileToMoveName;	// check if file exists
			writeLog( tempMessage );
			System.out.println( tempMessage);
			return false;
		}

		try {
			FileUtils.copyFile(fileToMove, fileNewLoc, true);	// copy to new location
			if (fileNewLoc.exists()) {
				FileUtils.deleteQuietly(fileToMove);			// delete orig if copy successful
			}
			tempMessage = "file: " + fileToMove.getAbsolutePath();
			writeLog( tempMessage );
			System.out.println( tempMessage );
			tempMessage = "moved to: " + fileNewLoc.getAbsolutePath();
			writeLog( tempMessage );
			System.out.println( tempMessage );
			success = true;
		} catch (IOException e) {
			e.printStackTrace();
			tempMessage = "***file not moved: " + fileToMoveName;
			writeLog( tempMessage );
			//System.out.println( tempMessage );
		}
		return success;
	}


	private static void readConfig(String configFile) {
		System.out.println("reading config file: " + configFile);

		// read config data from properties file using try with resources, means autoclose
		Properties prop = new Properties();
		try ( InputStream input = new FileInputStream( configFile )) {
			prop.load( input );
			//prop.load(new FileInputStream(configFile));

			//configFileDir = prop.getProperty("ConfigFilesDirectory");
			inputDir = prop.getProperty("InputFilesDirectory");
			inputDir = changeDirectorySeparator(inputDir);				// change file separator if Windows
			outputDir = prop.getProperty("OutputFilesDirectory");
			outputDir = changeDirectorySeparator(outputDir);			// change file separator if Windows
			processedDir = prop.getProperty("ProcessedFilesDirectory");
			processedDir = changeDirectorySeparator(processedDir);		// change file separator if Windows
			logFileDir = prop.getProperty("ProcessedFilesDirectory");
			logFile = makeFilePath(logFileDir, logFileName);
			dbDirectory = prop.getProperty("DbDirectory");
			dbName = prop.getProperty("DbName");
			inputFileNamePrefix = prop.getProperty("InputFileNamePrefix");
			inputFileNameSuffix = prop.getProperty("InputFileNameSuffix");

			/*
			masterFileMatch0 = prop.getProperty("MasterFileMatch0");
			masterFileMatch0 = changeDirectorySeparator(masterFileMatch0);	// change file separator if Windows
			masterFileMatch1 = prop.getProperty("MasterFileMatch1");
			masterFileMatch1 = changeDirectorySeparator(masterFileMatch1);	// change file separator if Windows
			masterFileMatch2 = prop.getProperty("MasterFileMatch2");
			masterFileMatch2 = changeDirectorySeparator(masterFileMatch2);	// change file separator if Windows
			masterFileMatch3 = prop.getProperty("MasterFileMatch3");
			masterFileMatch3 = changeDirectorySeparator(masterFileMatch3);	// change file separator if Windows
			masterFileMatch4 = prop.getProperty("MasterFileMatch4");
			masterFileMatch4 = changeDirectorySeparator(masterFileMatch4);	// change file separator if Windows
			masterFileMatch5 = prop.getProperty("MasterFileMatch5");
			masterFileMatch5 = changeDirectorySeparator(masterFileMatch5);	// change file separator if Windows
			 */

			String matchText = prop.getProperty("MatchingRules");				// get matching rules
			String[] tempArr = matchText.split(delimComma);						// split incoming text
			for (int i = 0; i < tempArr.length; i++) {	
				matchSequence.add( Integer.parseInt(tempArr[i]) );		// save match sequence as integer
			}

			writeLog("*****Starting Global Patient Match*****"); 
			writeLog("reading configuration file " + configFile);
			writeLog("match rule sequence: " + matchSequence); 

			//System.out.println("MasterFileMatch0: " + masterFileMatch0);
			//System.out.println("MasterFileMatch1: " + masterFileMatch1);
			//System.out.println("MasterFileMatch2: " + masterFileMatch2);
			//System.out.println("MasterFileMatch3: " + masterFileMatch3);
			//System.out.println("MasterFileMatch4: " + masterFileMatch4);
			//System.out.println("MasterFileMatch5: " + masterFileMatch5);
			System.out.println("match sequence: " + matchSequence);
			System.out.println("log file: " + logFile);

		} catch (Exception e) {
			//e.printStackTrace();
			System.out.println("**read config error message: "+e.getMessage());
			System.exit(1);
		}

		readAtomicIntegerSeed();		// read starting number to seed global Id generator
	}

	// read files from 1 input directory
	public static ArrayList<String> getFileNames1Dir(String directory, final String prefix, final String suffix) {

		ArrayList<String> matchingFiles = new ArrayList<String>();

		File dir = new File(directory);
		if (!dir.exists()) {
			System.out.println("***" + directory + " directory does not exist");
			return matchingFiles;
		}
		FilenameFilter fileFilter = new FilenameFilter() {		// create a dir filter
			public boolean accept (File dir, String name) {
				if (prefix.isEmpty()) {
					return name.endsWith( suffix );		// if can have only suffix
				} else {
					return name.startsWith( prefix ) && name.endsWith( suffix );  // if can have prefix + suffix
				}
			} 
		};

		//String[] files = dir.list(fileFilter);
		File[] paths = dir.listFiles(fileFilter);			// get files in dir returned by filter
		for (File path : paths) {
			matchingFiles.add(path.getAbsolutePath());		// get full file and directory path
		}
		return matchingFiles;
	}

	public static void readAtomicIntegerSeed() {
		File fileIn = new File(atomicIntegerPath);
		try ( BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(fileIn))) ) {
			String line = null;
			while ((line = br.readLine()) != null) {
				atomicIntegerSeed = Integer.valueOf( line.substring(0, line.indexOf(delimPipe)).trim());
				globalId = new AtomicInteger(atomicIntegerSeed);
				break;		// only 1 line in file so exit loop
			}
			String tempMessage = "Global Id starting seed: " + atomicIntegerSeed;
			writeLog( tempMessage );
			System.out.println( tempMessage );

		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		// try with resources --> autoclose

	}

	public static void writeAtomicIntegerSeed() {

		File fileOut = new File(atomicIntegerPath);
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(fileOut)) ) {

			Integer currGlobalId = globalId.get();		// get (but don't increment) current global Id
			String saveRecord = Integer.toString(currGlobalId) +" | "+ dateNowFormatted();
			bw.write(saveRecord);  		// write out record to be read in next restart

			String tempMessage = "Global Id ending seed: " + currGlobalId;
			writeLog( tempMessage );
			System.out.println( tempMessage );

		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// store to Temp table with integer primary key
	public static void storeToTempTableIdKey(Integer rowId, Integer gblId) {
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		String sql = "INSERT INTO " + TempTableIdKey + " ("
				+ "tempId,gblId) "
				+ "VALUES(?,?)";
		try ( PreparedStatement pstmt8 = db.prepareStatement(sql)) {
			pstmt8.setInt(1, rowId);
			pstmt8.setInt(2, gblId);
			pstmt8.executeUpdate();			// store to database
			//System.out.println("**stored to temp table: " + rowId);
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
		try ( PreparedStatement pstmt9 = db.prepareStatement("SELECT * FROM " + TempTableIdKey)) {
			ResultSet rset9 = pstmt9.executeQuery();
			while (rset9.next()) {
				System.out.println("temp id: " + rset9.getInt("tempId") + " gblid: " + rset9.getInt("gblId"));
			}
			if (rset9 != null) {
				rset9.close();
			}
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	// create temp table with integer primary key
	public static void createTempTableIdKey() {
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		boolean tableExists = false;
		String sql0 = "SELECT name FROM sqlite_temp_master WHERE type = 'table' AND name = '" +TempTableIdKey+ "'";
		try ( Statement stmt0 = db.createStatement() ) {
			ResultSet rs0 = stmt0.executeQuery(sql0);
			if (rs0.next()) {
				tableExists = true;		// check if table already exists
			} else {
				tableExists = false; 
			}
			if (rs0 != null) { rs0.close(); }
		} catch(SQLException ex) {
			System.out.println(ex.getMessage());
		}
		if (!tableExists) {
			String sqlCreate1 = "CREATE TEMP TABLE IF NOT EXISTS " + TempTableIdKey + " ("
					+ "tempId integer PRIMARY KEY,"
					+ "gblId integer)";				// SQL statement for creating a new table
			//String sqlCreate2 = "CREATE INDEX index1 ON " + TempTableIdKey + " (column1,column2)";
			try (	Statement stmt1 = db.createStatement() ) {
				//Statement stmt2 = db.createStatement()
				stmt1.execute(sqlCreate1);			// create a new table
				//stmt2.execute(sqlCreate2);		// create index
				System.out.println("new temp table created: " + TempTableIdKey);
			} catch (SQLException e) {
				System.out.println(e.getMessage());
			}
		}
		String sqlCreate3 = "DELETE FROM " + TempTableIdKey;	// delete existing records
		try ( Statement stmt3 = db.createStatement() ) {
			stmt3.execute(sqlCreate3);		// execute statement
			System.out.println("delete temp table records: " + TempTableIdKey);
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	// store to Temp table with text primary key
	public static void storeToTempTableTextKey(String nameKey, Integer rowId) {
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		String sql = "INSERT INTO " + TempTableTextKey + " ("
				+ "name,rowId) "
				+ "VALUES(?,?)";
		try ( PreparedStatement pstmt8 = db.prepareStatement(sql)) {
			pstmt8.setString(1, nameKey);
			pstmt8.setInt(2, rowId);
			pstmt8.executeUpdate();			// store to database
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}

		try ( PreparedStatement pstmt9 = db.prepareStatement("SELECT * FROM " + TempTableTextKey)) {
			ResultSet rset9 = pstmt9.executeQuery();
			while (rset9.next()) {
				System.out.println("temp name: " + rset9.getString("name") + " gblid: " + rset9.getInt("rowId"));
			}
			if (rset9 != null) {
				rset9.close();
			}
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	// create temp table with text primary key
	public static void createTempTableTextKey() {
		if (db == null) {
			connectDb();		// connect to database if no connection yet
		}
		boolean tableExists = false;
		String sql0 = "SELECT name FROM sqlite_temp_master WHERE type = 'table' AND name = '" +TempTableTextKey+ "'";
		try ( Statement stmt0 = db.createStatement() ) {
			ResultSet rs0 = stmt0.executeQuery(sql0);
			if (rs0.next()) {
				tableExists = true;		// check if table already exists
			} else {
				tableExists = false; 
			}
			if (rs0 != null) { rs0.close(); }
		} catch(SQLException ex) {
			System.out.println(ex.getMessage());
		}
		if (!tableExists) {
			String sqlCreate1 = "CREATE TEMP TABLE IF NOT EXISTS " + TempTableTextKey + " ("
					+ "name text,"
					+ "rowId integer)";				// SQL statement for creating a new table
			String sqlCreate2 = "CREATE INDEX index1 ON " + TempTableTextKey + " (name,rowId)";
			try ( Statement stmt1 = db.createStatement();
					Statement stmt2 = db.createStatement() ) {
				stmt1.execute(sqlCreate1);		// create a new table
				stmt2.execute(sqlCreate2);		// create index
				System.out.println("new temp table created: " + TempTableTextKey);
			} catch (SQLException e) {
				System.out.println(e.getMessage());
			}
		}
		String sqlCreate3 = "DELETE FROM " + TempTableTextKey;	// delete existing records
		try ( Statement stmt3 = db.createStatement() ) {
			stmt3.execute(sqlCreate3);		// execute statement
			System.out.println("delete temp table records: " + TempTableTextKey);
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}

	/*
	//Create a database
	public static void createNewDatabase(String dbName) {
		String url = "jdbc:sqlite:"+ dbDirectory + dbName;
		try (Connection conn = DriverManager.getConnection(url)) {
			if (conn != null) {
				DatabaseMetaData meta = conn.getMetaData();
				System.out.println("The driver name is " + meta.getDriverName());
				System.out.println("A new database has been created: " + dbName);
			}
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}
	// Create a new table in database
	public static void createNewTable() {
		String url = "jdbc:sqlite:" + dbDirectory + dbName;		// SQLite connection string
		String sql = "CREATE TABLE IF NOT EXISTS GlobalMatch (\n"	// SQL statement for creating new table
				+ "	id integer PRIMARY KEY,\n"
				+ "globalId integer,\n"
				+ "siteId text,\n"
				+ "projectId text,\n"
				+ "pidhash text NOT NULL,\n"
				+ "hash1 text,\n"
				+ "hash2 text,\n"
				+ "hash3 text,\n"
				+ "hash4 text,\n"
				+ "exception text\n"
				+ ");";
		try (Connection conn = DriverManager.getConnection(url);
				Statement stmt = conn.createStatement()) {
			stmt.execute(sql);			// create a new table
			System.out.println("new table created: GlobalMatch");
		} catch (SQLException e) {
			System.out.println(e.getMessage());
		}
	}
	 */

	public static void main(String[] args) {

		String argumentOne = "noargs";
		int processStep = 0;
		try {
			argumentOne = args[0];
			processStep = Integer.valueOf(argumentOne);
		}
		catch (ArrayIndexOutOfBoundsException e){
			System.out.println("no Step number found on startup");
			System.out.println("Valid Steps: 1=process input files and 2=run match rules.");
		}
		
		//processStep = 1;	//****** testing
		//processStep = 2;

		if (processStep <= 0 || processStep > 2) {
			System.out.println("Valid Steps: 1=process input files and 2=run match rules.");
			return;
		}

		// read configuration properties file from config subdirectory
		configRootPath = System.getenv("GLOBAL_MATCH_BASE");		// read root dir from System environment variable
		System.out.println("System environment variable GLOBAL_MATCH_BASE: " + configRootPath);
		configRootPath = changeDirectorySeparator(configRootPath);		// change file separator if Windows
		configFilePath = makeFilePath(makeFilePath(configRootPath, "config"), configFileName);  // get config path
		atomicIntegerPath = makeFilePath(makeFilePath(configRootPath, "config"), atomicIntegerFile);  // get path
		readConfig(configFilePath);			// read config file

		if (processStep == 1) {
			processInputFiles(processStep);		// go process input files in input dir
		} else {
			runMatchRules(processStep);			// go run match rules specified in config file
		}

		writeAtomicIntegerSeed();	// go save current value of next globalId

		if (db != null) {
			try {
				db.close();
			} catch (SQLException e) { /* ignored */}
		}
	}

}
