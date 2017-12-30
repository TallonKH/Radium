package main;

import javafx.application.*;
import javafx.fxml.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.*;
import javafx.scene.image.Image;
import javafx.scene.input.*;
import javafx.scene.media.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.stage.*;
import javafx.util.*;
import org.apache.commons.math3.analysis.interpolation.*;
import org.apache.commons.math3.analysis.polynomials.*;
import tMethods.*;
import tStringManager.*;

import javax.imageio.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.List;
import java.util.regex.*;

//57 fields!
@SuppressWarnings("FieldCanBeLocal")
public class AudioPlayer extends Application implements AudioSpectrumListener {
	@FXML
	public ImageView imageView;
	@FXML
	public Arc arc;
	@FXML
	public Circle circle;
	@FXML
	public Circle timePoint;
	@FXML
	public Label clock;
	@FXML
	public TextField songSearchbar;
	@FXML
	public Canvas canvas;

	//util
	private final static char FSEP = File.separatorChar; // OS File Separator Char - not to be used with URLs
	private final float TAU = (float) (Math.PI * 2);
	private final static Set<String> acceptedAudioTypes = new HashSet<>(Arrays.asList("mp3", "aif", "aiff", "wav", "fxm", "flv"));
	private Random random = new Random();
	//file stuff
	private String directoryFile = '/' + getClass().getProtectionDomain().getCodeSource().getLocation().getFile();

	{
		directoryFile = urlToFile(decode(directoryFile.substring(1, directoryFile.lastIndexOf('/') + 1)));
	}

	private Map<String, String> config;
	private String imageFolder;
	private String audioFolderPath;
	private String audioFolderURL;
	//data
	private List<String> songList = new ArrayList<>();
	private Deque<String> playedSongs = new ArrayDeque<>();
	private WordTrie songTree = new WordTrie();
	private String[] currentSongInfo = {"No Song Playing", "", "", ""}; //name, youtube id, url
	private String lastSong;
	private Map<String, String> directImgMatches = new HashMap<>();
	private Map<String, String> folderImgMatches = new HashMap<>();
	private Map<Pattern, String> regexImgMatches = new LinkedHashMap<>();
	//javafx
	private FXMLLoader loader;
	private GraphicsContext audioVizContext;
	private float centerX;
	private float centerY;
	private float canvasCenterX;
	private float canvasCenterY;
	//running vars
	private boolean paused;
	private boolean rewinding;
	private boolean fastForwarding;
	private boolean pointDragged;
	private float lastDragAngle;
	//changable settings
	private float updateInterval = 0.005f; // update interval for audiospectrum analyzer
	private int songStackSize = 5; // number of songs to keep the the rewind queue
	//player
	private MediaPlayer mediaPlayer;
	private Media song;
	//audiovisualizer math
	private int barPrecision = 2; // number of audioviz bars per audiospectrum band - [int >= 1] where 1 is least precise
	private int barCount; // # of audiovisualizer bars
	private int bandCount; // # of audiospectrum bands to calculate (actual # of bands is doubled to cut off outer frequencies)
	private int adjustedSize; // to account for smoothing the wrap point
	private double barIncrement; // dif 0-1 between each audioviz bar - for interpolation
	private double radianIncrement; // dif 0-2pi between each audioviz bar - for drawing
	private double magnitudeIncrement; // dif 0-1 between each audiospectrum band
	private double[] dMagnitudes; // magnitude input for the interpolator
	private double[] xs; // x-axis values for the interpolator
	private SplineInterpolator interpolator = new SplineInterpolator();
	//colors
	private ColorSourceMode baseColorMode = ColorSourceMode.PALETTE_FIRST;
	private ColorSourceMode barColorMode = ColorSourceMode.PALETTE_ALL;
	private Color defaultBaseColor = Color.gray(0.9725);
	private Color defaultBarColor = Color.gray(0.9725, 0.8);
	private double colorDiffThreshold = 0.1;
	private double colorFreqThreshold = 3;
	private List<Pair<Color, Integer>> palette = new ArrayList<>(Collections.singletonList(new Pair<>(defaultBaseColor, 1)));
	private boolean useDynamicBarOpacity = true;

	public enum ColorSourceMode {
		PALETTE_FIRST, PALETTE_ALL, DEFAULT
	}

	public static class WordTrie {
		private Map<LetterData, TrieNode> roots = new HashMap<>();

		public void print() {
			for (TrieNode node : roots.values()) {
				node.print(0);
			}
		}

		private List<Pair<String, Short>> searchRoot(String term) {
			if (term.length() == 0) {
				List<Pair<String, Short>> all = new ArrayList<>();
				for (TrieNode node : roots.values()) {
					all.addAll(node.collect(""));
				}
				return all;
			}
			TrieNode root = roots.get(new LetterData(term.charAt(0)));
			if (root == null) {
				return new ArrayList<>();
			}
			return root.search(term, "");
		}

		private TrieNode addValue(String val, short num) {
			LetterData firstLetter = new LetterData(val.charAt(0));
			TrieNode root = roots.get(firstLetter);
			BranchNode prevRoot = null;
			if (root == null) {
//				System.out.println("Added leaf as root node: " + val);
				TrieNode node = new LeafNode(val, num);
				roots.put(firstLetter, node);
				return node;
			} else {
				int charIndex = 0; //char index when scanning through val
				int prevIndex; //char index on last cycle
				int rootCharIndex; //char index of the current root's value
				int breakCause; //char index of the current root's value
				while (true) {
					prevIndex = charIndex;
					rootCharIndex = 0;
					String rootVal = root.getValue();

					if (root.getClass().equals(LeafNode.class)) {
						breakCause = 2;
						while (true) {
							boolean endV = charIndex == val.length();
							boolean endRV = rootCharIndex == rootVal.length();

							if (endRV && endV) {
								charIndex--;
								rootCharIndex--;
								break; // case 2
							}

							if (endRV) {
								breakCause = 0;
								break;
							}

							if (endV) {
								breakCause = 1;
								break;
							}

							if (val.charAt(charIndex) != rootVal.charAt(rootCharIndex)) {
								break; // case 2
							}
							charIndex++;
							rootCharIndex++;
						}
						switch (breakCause) {
							case 0: {//reached end of rootVal
//								System.out.println("Reached end of leaf " + rootVal + "; adding new " + val);
								LeafNode node = new LeafNode(val.substring(charIndex), num);
								BranchNode newRoot = new BranchNode(rootVal, true, root.getNum());
								newRoot.put(new LetterData(val.charAt(charIndex)), node);
								if (prevRoot == null) {
									roots.put(firstLetter, newRoot);
								} else {
									prevRoot.put(new LetterData(rootVal.charAt(0)), newRoot);
								}
								return node;
							}
							case 1: {//reached end of val
//								System.out.println("Reached end of new " + val + "; adding leaf " + rootVal);
								BranchNode newRoot = new BranchNode(val.substring(prevIndex), true, num);
								newRoot.put(new LetterData(rootVal.charAt(rootCharIndex)), new LeafNode(rootVal.substring(rootCharIndex), root.getNum()));
								if (prevRoot == null) {
									roots.put(firstLetter, newRoot);
								} else {
									prevRoot.put(new LetterData(val.charAt(prevIndex)), newRoot);
								}
								return newRoot;
							}
							case 2: {//found different character
								String commonStr = rootVal.substring(0, rootCharIndex);
//								System.out.println("Split leaf " + rootVal + " at " + commonStr + " adding new " + val);
								BranchNode newRoot = new BranchNode(commonStr, false, (short) -1);
								LeafNode node = new LeafNode(val.substring(charIndex), num);
								newRoot.put(new LetterData(val.charAt(charIndex)), node);
								newRoot.put(new LetterData(rootVal.charAt(rootCharIndex)), new LeafNode(rootVal.substring(rootCharIndex), root.getNum()));
								if (prevRoot == null) {
									roots.put(firstLetter, newRoot);
								} else {
									prevRoot.put(new LetterData(rootVal.charAt(0)), newRoot);
								}
								return node;
							}
						}
					} else {
						BranchNode rootAsBranch = (BranchNode) root;
						breakCause = 2;
						if (root.isValue() && rootVal.equals(val.substring(charIndex))) {
//							System.out.println("Replacing with val branch: " + val);
							BranchNode newRoot = new BranchNode(rootVal, true, num);
							newRoot.putAll(rootAsBranch);
							if (prevRoot == null) {
								roots.put(firstLetter, newRoot);
							} else {
								prevRoot.put(new LetterData(rootVal.charAt(0)), newRoot);
							}
							return newRoot;
						}
						while (true) {
							boolean endV = charIndex == val.length();
							boolean endRV = rootCharIndex == rootVal.length();
							if (endRV && endV) {
								charIndex--;
								rootCharIndex--;
								break; // case 2
							}

							if (endRV) {
								breakCause = 0;
								break;
							}

							if (endV) {
								breakCause = 1;
								break;
							}

							if (rootVal.charAt(rootCharIndex) != val.charAt(charIndex)) {
								break; // case 2
							}
							charIndex++;
							rootCharIndex++;
						}
						switch (breakCause) {
							case 0: {//end of rootval
								LetterData nextLetter = new LetterData(val.charAt(charIndex));
								TrieNode nextRoot = rootAsBranch.get(nextLetter);
								if (nextRoot == null) {
//									System.out.println("Reached end of branch " + rootVal + "; adding new " + val);
									LeafNode node = new LeafNode(val.substring(charIndex), num);
									rootAsBranch.put(nextLetter, node);
									return node;
								} else {
//									System.out.println("Moving from branch " + rootVal + " to " + nextRoot.getValue() + " when trying " + val);
									prevRoot = rootAsBranch;
									root = nextRoot;
								}
								break;
							}
							case 1: {//end of val
//								System.out.println("Reached end of new " + val + " on branch " + rootVal);
								LetterData nextLetter = new LetterData(rootVal.charAt(rootCharIndex));
								BranchNode newRoot = new BranchNode(val.substring(prevIndex), true, num);
								BranchNode newBranch = new BranchNode(rootVal.substring(rootCharIndex), root.isValue(), root.getNum());
								newBranch.putAll(rootAsBranch);
								newRoot.put(nextLetter, newBranch);

								if (prevRoot == null) {
									roots.put(firstLetter, newRoot);
								} else {
									prevRoot.put(new LetterData(val.charAt(prevIndex)), newRoot);
								}
								return newRoot;
							}
							case 2: {
								String commonStr = val.substring(prevIndex, charIndex);
								BranchNode newRoot = new BranchNode(commonStr, false, (short) -1);

								LeafNode node = new LeafNode(val.substring(charIndex), num);
								newRoot.put(new LetterData(val.charAt(charIndex)), node);

								BranchNode newBranch = new BranchNode(rootVal.substring(rootCharIndex), root.isValue(), root.getNum());
								newBranch.putAll(rootAsBranch);
								newRoot.put(new LetterData(rootVal.charAt(rootCharIndex)), newBranch);

								if (prevRoot == null) {
									roots.put(firstLetter, newRoot);
								} else {
									prevRoot.put(new LetterData(val.charAt(prevIndex)), newRoot);
								}
//								System.out.println("Split branch " + rootVal + " at " + commonStr + " adding new " + val);
								return node;
							}
						}
					}
				}
			}
		}

		public interface TrieNode {
			String getValue();

			short getNum();

			boolean isValue();

			void print(int depth);

			List<Pair<String, Short>> search(String term, String prev);

			List<Pair<String, Short>> collect(String prev);
		}

		public static class LeafNode implements TrieNode {
			private String value;
			private short num;

			private LeafNode(String value, short num) {
				this.value = value;
				this.num = num;
			}

			@Override
			public String getValue() {
				return value;
			}

			@Override
			public short getNum() {
				return num;
			}

			@Override
			public boolean isValue() {
				return true;
			}

			@Override
			public void print(int depth) {
				StringBuilder builder = new StringBuilder(depth);
				for (int i = 0; i < depth; i++) {
					builder.append("-=");
				}
				builder.append(value);
				System.out.println(builder.toString());
			}

			@Override
			public List<Pair<String, Short>> search(String term, String prev) {
				return getValue().startsWith(term) ? Collections.singletonList(new Pair<>(prev + value, num)) : new ArrayList<>();
			}

			@Override
			public List<Pair<String, Short>> collect(String prev) {
				return Collections.singletonList(new Pair<>(prev + value, num));
			}
		}

		public static class BranchNode extends HashMap<LetterData, TrieNode> implements TrieNode {
			private String value;
			private boolean isValue;
			private short num;

			private BranchNode(String value, boolean isValue, short num) {
				this.value = value;
				this.isValue = isValue;
				this.num = num;
			}

			@Override
			public short getNum() {
				return num;
			}

			/**
			 * returns a list of full values of child nodes, filtered by a search term
			 */
			public List<Pair<String, Short>> search(String term, String prev) {
				List<Pair<String, Short>> returns = new ArrayList<>();
				String combined = prev + value;

				if (term.length() > value.length()) {
					if (term.startsWith(value)) {
						for (TrieNode child : super.values()) {
							returns.addAll(child.search(term.substring(value.length()), combined));
						}
					}
				} else {
					if (value.startsWith(term)) {
						if (isValue) {
							returns.add(new Pair<>(combined, num));
						}
						returns.addAll(collect(prev));
					}
				}
				return returns;
			}

			/**
			 * returns a list of full values of child nodes, unfiltered
			 */
			public List<Pair<String, Short>> collect(String prev) {
				List<Pair<String, Short>> returns = new ArrayList<>();
				String combined = prev + value;
				if (isValue) {
					returns.add(new Pair<>(combined, num));
				}

				for (TrieNode child : super.values()) {
					returns.addAll(child.collect(combined));
				}
				return returns;
			}

			@Override
			public String getValue() {
				return value;
			}

			@Override
			public boolean isValue() {
				return isValue;
			}

			@Override
			public void print(int depth) {
				StringBuilder builder = new StringBuilder(depth);
				for (int i = 0; i < depth; i++) {
					builder.append("-/");
				}
				if (!isValue) {
					builder.append('*');
				}
				builder.append(value);
				System.out.println(builder.toString());
				for (TrieNode child : super.values()) {
					child.print(depth + 1);
				}
			}
		}

		public static class LetterData {
			byte byt;

			private LetterData(char ch) {
				byt = (byte) ch;
			}

			@Override
			public int hashCode() {
				return byt;
			}

			@SuppressWarnings("EqualsWhichDoesntCheckParameterClass")
			@Override
			public boolean equals(Object o) {
				return o.hashCode() == hashCode();
			}
		}
	}

	@FXML
	public void initialize() {
		canvasCenterX = (int) (canvas.getWidth() / 2);
		canvasCenterY = (int) (canvas.getHeight() / 2);

		final Circle clip = new Circle(175, 175, 175);
		imageView.setClip(clip);

		audioVizContext = canvas.getGraphicsContext2D();
		audioVizContext.setLineCap(StrokeLineCap.ROUND);
	}

	public static void main(String args[]) {
		launch(args);
	}

	@Override
	public void start(Stage mainStage) throws Exception {
		print("Directory: " + directoryFile);

		mainStage.initStyle(StageStyle.TRANSPARENT);
		mainStage.setTitle("THAudioPlayer");
		mainStage.setOnCloseRequest((o) -> quit());
		mainStage.show();

		loader = new FXMLLoader(getClass().getResource("fxml/player.fxml"));
		loader.setController(this);
		loader.load();
		Scene scene = new Scene(loader.getRoot(), 800, 800);
		scene.setFill(Color.TRANSPARENT);
		mainStage.setScene(scene);

		//move to center
		Rectangle2D screenBounds = Screen.getPrimary().getBounds();
		centerX = (float) (screenBounds.getWidth() / 2);
		centerY = (float) (screenBounds.getHeight() / 2);
		mainStage.setX(centerX - scene.getWidth() / 2);
		mainStage.setY(centerY - scene.getHeight() / 2);

		readConfig();
		findSongs();
		readMatches();

		songSearchbar.setAlignment(Pos.CENTER);
		songSearchbar.focusedProperty().addListener((observable, oldValue, newValue) -> {
			if (!newValue) {
				songSearchbar.setText(currentSongInfo[0]);
			}
		});
		songSearchbar.setOnAction(event -> {
			String term = simplifySongName(songSearchbar.getText());
			if (term.length() == 0) {
				songSearchbar.setText(currentSongInfo[0]);
			} else {
				List<Pair<String, Short>> search = songTree.searchRoot(term);
				print(search);
				if (search.size() > 0) {
					if (lastSong != null) {
						playedSongs.addLast(lastSong);
					}
					Pair<String, Short> shortest = Collections.min(search, Comparator.comparingInt(a -> a.getKey().length()));
					playSong(songList.get(shortest.getValue()));
				}
			}
		});

		mainStage.getIcons().add(new Image(getClass().getResourceAsStream("resources/logo.png")));
		if (SystemTray.isSupported()) {
			TrayIcon trayIcon;
			print("Setting up tray icon...");
			SystemTray tray = SystemTray.getSystemTray();
			java.awt.Image iconImg;

			try (InputStream stream = getClass().getResource("resources/icon16.png").openStream()) {
				iconImg = ImageIO.read(stream);
			} catch (IOException e1) {
				e1.printStackTrace();
				return;
			}
			PopupMenu popup = new PopupMenu();
			java.awt.MenuItem exitButton = new java.awt.MenuItem("Quit");
			exitButton.addActionListener(e -> quit());
			popup.add(exitButton);

			java.awt.MenuItem openFolder = new java.awt.MenuItem("Folder");
			openFolder.addActionListener(e -> {
				try {
					Desktop.getDesktop().open(new File(directoryFile));
				} catch (IOException e1) {
					e1.printStackTrace();
				}
			});
			popup.add(openFolder);
			trayIcon = new TrayIcon(iconImg, "THAudioPlayer", popup);
			try {
				tray.add(trayIcon);
			} catch (AWTException e) {
				e.printStackTrace();
			}
		}
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s, "UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
			return null;
		}
	}

	private static String getExtension(String path) {
		return path.substring(path.lastIndexOf('.') + 1);
	}

	private static String fileToUrl(String url) {
		char[] chars = url.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == FSEP) {
				chars[i] = '/';
			}
		}
		return "file:/" + new String(chars);
	}

	private static String urlToFile(String file) {
		char[] chars = file.toCharArray();
		for (int i = 0; i < chars.length; i++) {
			if (chars[i] == '/') {
				chars[i] = FSEP;
			}
		}
		return new String(chars);
	}

	private void setColorFreqThreshold(double threshold) {
		this.colorFreqThreshold = threshold;
		config.put("ColorFreqThreshold", Double.toString(threshold));
		updateColorPalette();
	}

	private void setColorDiffThreshold(double threshold) {
		this.colorDiffThreshold = threshold;
		config.put("ColorDiffThreshold", Double.toString(threshold));
		updateColorPalette();
	}

	private void setUseDynamicBarOpacity(boolean useDynamicBarOpacity) {
		this.useDynamicBarOpacity = useDynamicBarOpacity;
		config.put("DynamicOpacity", Boolean.toString(useDynamicBarOpacity));
		if (!useDynamicBarOpacity) {
			if (barColorMode.equals(ColorSourceMode.PALETTE_FIRST)) {
				audioVizContext.setStroke(palette.get(0).getKey());
			} else if (barColorMode.equals(ColorSourceMode.DEFAULT)) {
				audioVizContext.setStroke(defaultBarColor);
			}
		}
	}

	private void setBarColorMode(ColorSourceMode mode) {
		this.barColorMode = mode;
		config.put("BarColorMode", mode.name());
		switch (mode) {
			case PALETTE_ALL:
				// this will be handled per audio spectrum update
				break;
			case PALETTE_FIRST:
				audioVizContext.setStroke(palette.get(0).getKey());
				break;
			case DEFAULT:
				audioVizContext.setStroke(defaultBarColor);
				break;
		}
	}

	private void setDefaultBarColor(Color color) {
		this.defaultBarColor = color;
		config.put("BarColor", color.toString());
		if (barColorMode.equals(ColorSourceMode.DEFAULT)) {
			audioVizContext.setStroke(color);
		}
	}

	private void setDefaultBaseColor(Color color) {
		this.defaultBaseColor = color;
		config.put("BaseColor", color.toString());
		if (baseColorMode.equals(ColorSourceMode.DEFAULT)) {
			circle.setStroke(defaultBaseColor);
		} else {
			circle.setStroke(palette.get(0).getKey());
		}
	}

	private void setBaseColorMode(ColorSourceMode mode) {
		this.baseColorMode = mode;
		config.put("BaseColorMode", mode.name());
		switch (mode) {
			case PALETTE_ALL:
			case PALETTE_FIRST:
				circle.setStroke(palette.get(0).getKey());
				break;
			case DEFAULT:
				circle.setStroke(defaultBaseColor);
				break;
		}
	}

	private void setBarCount(int barCount) {
		this.barCount = barCount;
		config.put("BarCount", Integer.toString(barCount));
		bandCount = barCount / barPrecision;
		barIncrement = 1.0 / barCount;
		radianIncrement = Math.PI / barCount * 2;

//		int adjustedSize = (int) (bandCount * 1.1);
//		if (adjustedSize % 2 != 0) {
//			adjustedSize++;
//		}
//		dMagnitudes = new double[adjustedSize];
//		xs = new double[adjustedSize];
//		loopDiff = (adjustedSize - bandCount) / 2;

		adjustedSize = (int) (bandCount * 0.95);
		dMagnitudes = new double[bandCount];
		xs = new double[bandCount];

		magnitudeIncrement = 1.0 / (bandCount - 1);

		if (mediaPlayer != null) {
			mediaPlayer.setAudioSpectrumNumBands(barCount * 2);
		}

		audioVizContext.setLineWidth(700 / barCount);
	}

	@Override
	public void spectrumDataUpdate(double timestamp, double duration, float[] magnitudes, float[] phases) {
		audioVizContext.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

		// progress indicators
		float radians = (float) (mediaPlayer.getCurrentTime().toMillis() / song.getDuration().toMillis()) * TAU;
		updateArc(radians);
		updateClockDisplay(radians);

		// audio visualizer bands
		for (int i = 0; i < adjustedSize; i++) {
			dMagnitudes[i] = Math.max((magnitudes[i] + 60) * 3 + 10, dMagnitudes[i] * 0.99);
			xs[i] = magnitudeIncrement * i;
		}
		for (int i = 0, l = (bandCount - adjustedSize); i < l; i++) {
			dMagnitudes[i + adjustedSize] = dMagnitudes[i];
			xs[i + adjustedSize] = 1 + (magnitudeIncrement * i);
		}

		PolynomialSplineFunction spline = interpolator.interpolate(xs, dMagnitudes);
		for (int i = 0; i < barCount; i++) {
			double mag = spline.value(barIncrement * i);
			double rads = i * radianIncrement;
			double sin = Math.sin(rads);
			double cos = Math.cos(rads);
			double len = 200 + mag;
			if (barColorMode.equals(ColorSourceMode.PALETTE_ALL)) {
				if (palette != null && !palette.isEmpty()) {
					Pair<Color, Integer> pair = palette.get(i % palette.size());
					Color c = pair.getKey();
					if (useDynamicBarOpacity) {
						audioVizContext.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(),
															  TMath.lerp(0, 1, TMath.clamp(mag / 100, 0, 1), TMath.LoopModes.STOP)));
					} else {
						audioVizContext.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.8));
					}
				}
			} else {
				if (useDynamicBarOpacity) {
					float alpha = (float) TMath.clamp(TMath.lerp(0, 1, mag / 100, TMath.LoopModes.STOP), 0, 1);
					if (barColorMode.equals(ColorSourceMode.PALETTE_FIRST)) {
						audioVizContext.setStroke(TColorsFX.setAlpha(palette.get(0).getKey(), alpha));
					} else {
						//color default mode
						audioVizContext.setStroke(TColorsFX.setAlpha(defaultBarColor, alpha));
					}
				}
			}
			audioVizContext.strokeLine(canvasCenterX + 190 * sin, canvasCenterY + 190 * cos, canvasCenterX + len * sin, canvasCenterY + len * cos);
		}
	}

	private void updateClockDisplay(float radians) {
		int totalTime = (int) song.getDuration().toSeconds();
		int currentTime = (int) (radians / TAU * totalTime);
		Platform.runLater(() -> clock.setText(formatTime(currentTime) + " / " + formatTime(totalTime)));

	}

	private String formatTime(int totalSeconds) {
		int minutes = totalSeconds / 60;
		if (minutes >= 60) {
			return minutes / 60 + ":" + fixTimeLength(minutes % 60) + ":" + fixTimeLength(totalSeconds % 60);
		}
		return minutes + ":" + fixTimeLength(totalSeconds % 60);
	}

	private String fixTimeLength(int time) {
		if (time < 10) {
			return "0" + time;
		}
		return Integer.toString(time);
	}

	private void updateArc(float radians) {
		radians *= -1;
		timePoint.setTranslateX(-Math.sin(radians) * 170);
		timePoint.setTranslateY(-Math.cos(radians) * 170);
		arc.setLength(Math.toDegrees(radians));
	}

	private void playSong(String name) {
		if (mediaPlayer != null) {
			mediaPlayer.stop();
			mediaPlayer.dispose();
			mediaPlayer.setAudioSpectrumListener(null);
			mediaPlayer = null;
		}

		song = new Media(audioFolderURL + name);
		mediaPlayer = new MediaPlayer(song);
		mediaPlayer.setAudioSpectrumNumBands(bandCount * 2);
		mediaPlayer.setAudioSpectrumListener(this);
		mediaPlayer.setAudioSpectrumInterval(updateInterval);
		mediaPlayer.setOnEndOfMedia(this::nextSong);

		if (!paused) {
			mediaPlayer.play();
		}

		currentSongInfo = getSongInfo(decode(song.getSource()));
		print("Now playing: " + currentSongInfo[0]);
		songSearchbar.setText(currentSongInfo[0]);

		// Try direct match
		String imgUrl = directImgMatches.get(currentSongInfo[1]);

		if (imgUrl != null) {
			print("Matched to [" + imgUrl + "] (direct)");
		} else {
			// Try regex match
			for (Map.Entry<Pattern, String> entry : regexImgMatches.entrySet()) {
				if (entry.getKey().matcher(currentSongInfo[0]).matches()) {
					imgUrl = entry.getValue();
					print("Matched to [" + imgUrl + "] (regex)");
					break;
				}
			}
		}

		// Try folder match
		if (imgUrl == null) {
			imgUrl = folderImgMatches.get(currentSongInfo[2]);
			if (imgUrl != null) {
				print("Matched to [" + imgUrl + "] (folder)");
			}
		}


		Image image = null;
		if (imgUrl != null) {
			String fullUrl = "file:/" + imageFolder + imgUrl;
			if (imgUrl.charAt(imgUrl.length() - 1) == '/') {
				//imgUrl leads to folder, pick random image from folder
				File[] images = new File(urlToFile(fullUrl)).listFiles();
				if (images != null) {
					try {
						image = new Image(images[random.nextInt(images.length)].toURI().toURL().toString());
					} catch (MalformedURLException e) {
						e.printStackTrace();
					}
				}
			} else {
				//imgUrl leads to image
				image = new Image(fullUrl);
			}
		} else {
			print("Grabbing thumbnail...");
			Image temp = new Image("http://img.youtube.com/vi/" + currentSongInfo[1] + "/0.jpg");
			if (temp.getWidth() > 1) {
				image = smartCrop(temp);
			} else {
				image = new Image(getClass().getResourceAsStream("resources/logo.png"));
			}
		}


		if (image != null && !image.isError()) {
			imageView.setImage(image);
			updateColorPalette();
		}

		lastSong = name;
	}

	private void nextSong() {
		if (playedSongs.size() >= songStackSize) {
			playedSongs.removeFirst();
		}
		String name = songList.get(random.nextInt(songList.size()));
		if (lastSong != null) {
			playedSongs.addLast(lastSong);
		}
		playSong(name);
	}

	private void recursiveSongGrab(File directory, List<String> list) {
		//noinspection ConstantConditions
		for (File file : directory.listFiles()) {
			if (file.isDirectory()) {
				recursiveSongGrab(file, list);
			} else {
				if (acceptedAudioTypes.contains(getExtension(file.getName()))) {
					list.add(file.toURI().toString().substring(this.audioFolderURL.length()));
				}
			}
		}
	}

	private void findSongs() {
		songList.clear();
		recursiveSongGrab(new File(audioFolderPath), songList);
		songTree = new WordTrie();
		short index = 0;
		for (String s : songList) {
			s = simplifySongPath(s);
			if (s.length() > 0) {
				songTree.addValue(s, index);
			}
			index++;
		}
		print(songList.size() + " audio clips found");
	}

	private void print(Object o) {
		System.out.println(o);
	}

	private void readConfig() {
		try (BufferedReader reader = new BufferedReader(new FileReader(directoryFile + "config.txt"))) {
			StringBuilder builder = new StringBuilder();
			reader.lines().forEach(builder::append);
			config = TFiles.parseMap(builder.toString());

			audioFolderPath = config.get("AudioFolder");
			if (audioFolderPath.charAt(0) == '~') {
				audioFolderPath = directoryFile + audioFolderPath.substring(1);
			}
			audioFolderURL = fileToUrl(audioFolderPath);
			print("Audio source folder set to " + audioFolderPath);

			String imageFolderPath = config.get("ImageFolder");
			if (imageFolderPath.charAt(0) == '~') {
				imageFolderPath = directoryFile + imageFolderPath.substring(1);
			}
			imageFolder = imageFolderPath;

			print("Grabbing images from " + imageFolderPath);

			songStackSize = Integer.parseInt(config.getOrDefault("StackSize", "5"));
			setDefaultBaseColor(Color.web(config.getOrDefault("BaseColor", "#F8F8F8")));
			setDefaultBarColor(Color.web(config.getOrDefault("BarColor", "#FFFFFFCC")));
			setUseDynamicBarOpacity(config.getOrDefault("DynamicOpacity", "true").equals("true"));
			setColorDiffThreshold(Double.parseDouble(config.getOrDefault("ColorDiffThreshold", "0.4")));
			setColorFreqThreshold(Double.parseDouble(config.getOrDefault("ColorFreqThreshold", "4")));
			setBarCount(Integer.parseInt(config.getOrDefault("BardCount", "128")));
			setBarColorMode(ColorSourceMode.valueOf(config.getOrDefault("BarColorMode", "PALETTE_ALL")));

		} catch (FileNotFoundException e) {
			print("Unable to read config - config not located at " + directoryFile + "config.txt");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private String simplifySongPath(String s) {
		StringBuilder songNameBuilder = new StringBuilder(16);
		//noinspection ConstantConditions
		for (char c : decode(s.substring(s.lastIndexOf('/'))).toCharArray()) {
			if (c == '@' || c == '.') {
				break;
			}
			if ((c >= 'a' && c <= 'z') ||
				(c >= '0' && c <= '9') ||
				(c == ' ')) {
				songNameBuilder.append(c);
			} else if (c >= 'A' && c <= 'Z') {
				songNameBuilder.append((char) (c | (byte) 0b00100000));
			}
		}
		return songNameBuilder.toString();
	}

	private String simplifySongName(String s) {
		StringBuilder songNameBuilder = new StringBuilder(8);
		for (char c : s.toCharArray()) {
			if ((c >= 'a' && c <= 'z') ||
				(c >= '0' && c <= '9') ||
				(c == ' ')) {
				songNameBuilder.append(c);
			} else if (c >= 'A' && c <= 'Z') {
				songNameBuilder.append((char) (c | (byte) 0b00100000));
			}
		}
		return songNameBuilder.toString();
	}

	private void readMatches() {
		directImgMatches.clear();
		folderImgMatches.clear();
		regexImgMatches.clear();
		List<String> failed = new ArrayList<>();
		String audioFolderPathURI = fileToUrl(audioFolderPath);
		try (BufferedReader reader = new BufferedReader(new FileReader(directoryFile + "matches.txt"))) {
			while (true) {
				String pair = reader.readLine();
				if (pair == null) {
					break;
				}

				pair = pair.replace("\t", "");

				// ignore empty bars & comment bars
				if (pair.length() > 3 && pair.charAt(0) != '#') {
					int split = pair.lastIndexOf(':');

					if (split < 1 || split >= pair.length() - 1) {
						failed.add("Failed to parse pair: " + pair);
					} else {
						String key = pair.substring(0, split);
						String val = pair.substring(split + 1);
						if (key.charAt(0) == '~') {
							key = key.substring(1);
							regexImgMatches.put(Pattern.compile(key), val);
						} else if (key.charAt(0) == '@') {
							folderImgMatches.put(audioFolderPathURI + key.substring(1), val);
						} else {
							directImgMatches.put(key, val);
						}
					}
				}
			}
		} catch (FileNotFoundException e) {
			print("Unable to find matches.txt");
		} catch (IOException e) {
			e.printStackTrace();
		}

		print("Direct Matches Loaded: " + directImgMatches.size());
		print("Regular Expressions Loaded: " + regexImgMatches.size());
		print("Folder Matches Loaded: " + folderImgMatches.size());
		if (failed.size() > 0) {
			print(failed.size() + " failed to load:");
			for (String err : failed) {
				print("\t" + err);
			}
		}
	}

	private void quit() {
		if (mediaPlayer != null) {
			mediaPlayer.stop();
			mediaPlayer.dispose();
		}
		updateConfig();
		Platform.exit();
		System.exit(1);
	}

	/**
	 * {Name, ID, url}
	 */
	private String[] getSongInfo(String src) {
		int indexA = src.lastIndexOf('/');
		int indexB = src.lastIndexOf('@');
		int indexC = src.lastIndexOf('.');
		if (indexB > -1) {
			return new String[]{src.substring(indexA + 1, indexB),
								src.substring(indexB + 1, indexC),
								src.substring(0, indexA)};
		} else {
			return new String[]{src.substring(indexA + 1, indexC),
								null,
								src.substring(0, indexA)};
		}
	}

	private void updateColorPalette() {
		palette = TColorsFX.getColorPaletteFast(imageView.getImage(), colorDiffThreshold);

		if (palette.isEmpty()) {
			palette = new ArrayList<>(Collections.singletonList(new Pair<>(defaultBaseColor, 1)));
		}

		if ((barColorMode.equals(ColorSourceMode.PALETTE_ALL) || baseColorMode.equals(ColorSourceMode.PALETTE_ALL)) &&
			palette.size() > 1) {

			int mostFreq = palette.get(0).getValue();
			int stdev = 0;
			for (Pair<Color, Integer> p : palette) {
				stdev += Math.pow(p.getValue() - mostFreq, 2);
			}
			stdev = (int) (Math.sqrt(stdev / (palette.size() - 1)) * colorFreqThreshold);
			int i = 0;
			for (; i < palette.size(); i++) {
				if (mostFreq - palette.get(i).getValue() > stdev) {
					break;
				}
			}
			if (i >= barCount) {
				i = barCount;
			} else {
				// make sure bars loop evenly around
				while (barCount % i != 0) {
					i--;
				}
			}
			i = Math.max(1, i);
			palette = palette.subList(0, Math.max(2, i - (barCount % i)));
		} else {
			if (barColorMode.equals(ColorSourceMode.PALETTE_FIRST)) {
				audioVizContext.setStroke(palette.get(0).getKey());
			}
		}

		if (!baseColorMode.equals(ColorSourceMode.DEFAULT)) {
			circle.setStroke(palette.get(0).getKey());
		}
	}


	private Image smartCrop(Image image) {
		PixelReader reader = image.getPixelReader();
		int imgWidth = (int) image.getWidth();
		int imgHeight = (int) image.getHeight();
		float cropLimit = 0.3f;
		int maxCropWidth = (int) (imgWidth * cropLimit);
		int maxCropHeight = (int) (imgHeight * cropLimit);
		int mx = 0;
		outer:
		for (; mx < maxCropWidth; mx++) {
			for (int y = 0; y < imgHeight; y++) {
				if (reader.getArgb(mx, y) != -16777216) {
					break outer;
				}
			}
		}
		int my = 0;
		outer:
		for (; my < maxCropHeight; my++) {
			for (int x = 0; x < imgWidth; x++) {
				if (reader.getArgb(x, my) != -16777216) {
					break outer;
				}
			}
		}
		int cropRadius = Math.min(imgWidth - 2 * mx, imgHeight - 2 * my);

		return new WritableImage(reader, (imgWidth - cropRadius) / 2, (imgHeight - cropRadius) / 2, cropRadius,
								 cropRadius);
	}

	private Color getAvgColor(Image image) {
		PixelReader reader = image.getPixelReader();
		int w = (int) image.getWidth();
		int h = (int) image.getHeight();
		int num = w * h;
		double r = 0;
		double g = 0;
		double b = 0;
		for (int x = 0; x < w; x++) {
			for (int y = 0; y < h; y++) {
				Color c = reader.getColor(x, y);
				r += c.getRed();
				g += c.getGreen();
				b += c.getBlue();
			}
		}
		return Color.color((r / num), (g / num), (b / num), 0.8);
	}

	private void updateConfig() {
		print("Writing settings to config...");
		String configPath = directoryFile + "config.txt";
		try (FileWriter writer = new FileWriter(configPath)) {
			writer.write(TFiles.composeMap(config));
		} catch (FileNotFoundException e) {
			print("Unable to write to config - config not located at " + directoryFile + "config.txt");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	@FXML
	public void skipBack(MouseEvent mouseEvent) {
		String prev = playedSongs.pollLast();
		if (prev != null) {
			playSong(prev);
		}
	}

	@FXML
	public void skipForward(MouseEvent mouseEvent) {
		if (!mouseEvent.isShiftDown()) {
			nextSong();
		}
	}

	@FXML
	public void forwardPressed(MouseEvent mouseEvent) {
		if (mouseEvent.isShiftDown()) {
			//TODO F1X TH1S
			//TODO when holding shift, replace with fast forward icon
			fastForwarding = true;
			mediaPlayer.setRate(2);
		}
	}

	@FXML
	public void forwardReleased(MouseEvent mouseEvent) {
		if (fastForwarding) {
			fastForwarding = false;
			mediaPlayer.setRate(1);
		}
	}

	@FXML
	public void backPressed(MouseEvent mouseEvent) {
		//TODO replace with restart song button - make icon also
		if (mouseEvent.isShiftDown()) {

		}
	}

	@FXML
	public void backReleased(MouseEvent mouseEvent) {
		if (rewinding) {
			rewinding = false;
			mediaPlayer.setRate(1);
		}
	}

	@FXML
	public void pointPressed(MouseEvent mouseEvent) {
		if (mediaPlayer != null) {
			pointDragged = true;
			lastDragAngle = (float) (mediaPlayer.getCurrentTime().toMillis() / song.getDuration().toMillis()) * TAU;
			mediaPlayer.pause();
		}
	}

	@FXML
	public void pointDragged(MouseEvent mouseEvent) {
		if (pointDragged) {
			float newDragAngle = (float) Math.atan2(centerX - mouseEvent.getScreenX(), centerY - mouseEvent.getScreenY());
			newDragAngle = newDragAngle < 0 ? -newDragAngle : TAU - newDragAngle;
			if (lastDragAngle > Math.PI) {
				newDragAngle = newDragAngle > 2 ? newDragAngle : TAU;
			} else {
				newDragAngle = newDragAngle < (TAU - 2) ? newDragAngle : 0;
			}
			lastDragAngle = newDragAngle;
			updateArc(lastDragAngle);
			updateClockDisplay(lastDragAngle);
		}
	}

	@FXML
	public void pointReleased(MouseEvent mouseEvent) {
		if (pointDragged) {
			pointDragged = false;
			if (TAU - lastDragAngle > 0.001) {
				mediaPlayer.seek(Duration.millis(song.getDuration().toMillis() * lastDragAngle / TAU));
			} else {
				//if seeker is basically at end, next song
				nextSong();
			}
			if (!paused) {
				mediaPlayer.play();
			}
		}
	}

	@FXML
	public void playClicked(MouseEvent mouseEvent) {
		//TODO when holding shift, replace with Loop option - don't forget to make loop/unloop icon
		if (mouseEvent.getButton().equals(MouseButton.PRIMARY) && mediaPlayer != null) {
			if (paused) {
				paused = false;
				mediaPlayer.play();
			} else {
				paused = true;
				mediaPlayer.pause();
			}
		}
	}
}