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
import javafx.scene.paint.Paint;
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
import java.nio.file.*;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.regex.*;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

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
	@FXML
	public ImageView centerButton;
	@FXML
	public ImageView leftButton;
	@FXML
	public ImageView rightButton;

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
	private String imageFolderPath;
	private String imageFolderUrl;
	private String audioFolderPath;
	private String audioFolderUrl;
	private WatchService audioDirWatcher;
	//data
	private List<StringPair> audioList = new ArrayList<>(); // list of song names/urls, sorted by name size
	private ArrayList<Integer> playedSongs; // initialized in config
	private String[] currentSongInfo = {"Radium", "", "", ""}; //name, youtube id, url
	private int prevSongIndex;
	private int currentSongIndex;
	//	private boolean addPrevToStack;
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
	private boolean looping;
	private boolean pointDragged;
	private float lastDragAngle;
	private boolean altDown;
	private boolean shiftDown;
	private boolean ctrlDown;
	//changable settings
	private float updateInterval = 0.016666667f; // update interval for audiospectrum analyzer
	private int songStackSize = 5; // number of songs to keep the the rewind stack
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
	private List<Pair<Color, Integer>> palette = Collections.singletonList(new Pair<>(defaultBaseColor, 1));
	private boolean useDynamicBarOpacity = true;
	private Map<String, DynamicColorer> colorers = new HashMap<>();

	{
		colorers.put("ENGLISH", new DynamicColorer() {
			private List<Color> colors = Arrays.asList(Color.web("FFFF00"), Color.web("0000FF"), Color.web("FF0000"), Color.web("800080"),
													   Color.web("FF4500"), Color.web("008000"), Color.web("800000"));
			private Color felt = Color.web("32CD3200");
			private Random random = new Random();

			@Override
			public void initialize(int barCount, List<Pair<Color, Integer>> imagePalette) {

			}

			@Override
			public int setBarCount() {
				return 128;
			}

			@Override
			public Paint getBase(float loudness) {
				return Color.BLACK;
			}

			@Override
			public Paint getBar(int barIndex, float loudness) {
				return barIndex % 8 == 0 ? Color.BLACK : TColorsFX.colorLerp(felt, colors.get(random.nextInt(colors.size())), loudness);
			}
		});
		colorers.put("PORTAL", new DynamicColorer() {
			private Color blue = Color.color(0.1647, 0.8118, 0.9725);
			private Color orange = Color.color(0.9333, 0.5216, 0.0902);
			double timer = 0;

			@Override
			public void initialize(int barCount, List<Pair<Color, Integer>> imagePalette) {

			}

			@Override
			public int setBarCount() {
				return 128;
			}

			@Override
			public Paint getBase(float loudness) {
				timer += updateInterval;
				return Color.gray(0.8);
			}

			@Override
			public Paint getBar(int barIndex, float loudness) {
				return (barIndex) % 8 == 0 ? Color.gray(0.8) : TColorsFX.setAlpha(TColorsFX.colorLerp(blue, orange, (timer + barIndex / 16.0) % 2, TMath.LoopModes.BOUNCE), loudness);
			}
		});
		colorers.put("DARK", new DynamicColorer() {
			@Override
			public void initialize(int barCount, List<Pair<Color, Integer>> imagePalette) {

			}

			@Override
			public int setBarCount() {
				return 128;
			}

			@Override
			public Paint getBase(float loudness) {
				return Color.BLACK;
			}

			@Override
			public Paint getBar(int barIndex, float loudness) {
				return TColorsFX.colorLerp(Color.BLACK, Color.DARKRED, loudness * 3, TMath.LoopModes.BOUNCE);
			}
		});
	}

	private DynamicColorer barColorer = null;//colorers.get("DARK");

	public enum ColorSourceMode {
		PALETTE_FIRST, PALETTE_ALL, DEFAULT
	}

	/**
	 * It's just a pair of Strings.
	 **/
	public static class StringPair {
		public final String strA;
		public final String strB;

		public StringPair(String a, String b) {
			strA = a;
			strB = b;
		}

		@Override
		public boolean equals(Object o) {
			if (!o.getClass().equals(this.getClass())) {
				return false;
			}
			StringPair other = (StringPair) o;
			return other.strA.equals(strA) && other.strB.equals(strB);
		}

		@Override
		public int hashCode() {
			return strA.hashCode() ^ strB.hashCode();
		}
	}

	public interface DynamicColorer {
		void initialize(int barCount, List<Pair<Color, Integer>> imagePalette);

		int setBarCount();

		Paint getBar(int barIndex, float loudness);

		Paint getBase(float loudness);
	}

	public static void main(String args[]) {
		launch(args);
	}

	@FXML
	public void initialize() {
		canvasCenterX = (int) (canvas.getWidth() / 2);
		canvasCenterY = (int) (canvas.getHeight() / 2);

		final Circle clip = new Circle(175, 175, 175);
		imageView.setClip(clip);
		audioVizContext = canvas.getGraphicsContext2D();
		audioVizContext.setLineCap(StrokeLineCap.ROUND);

		centerButton.addEventHandler(MouseEvent.MOUSE_CLICKED, (e) -> {
			if (e.getButton().equals(MouseButton.PRIMARY) && mediaPlayer != null) {
				if (altDown) {
					if (looping) {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/noloop.png")));
					} else {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/loop.png")));
					}
					looping = !looping;
				} else {
					if (paused) {
						mediaPlayer.play();
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/pause.png")));
					} else {
						mediaPlayer.pause();
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/play.png")));
					}
					paused = !paused;
				}
			}
		});

		centerButton.addEventHandler(MouseEvent.MOUSE_ENTERED, (e) -> centerButton.setOpacity(0.4));
		centerButton.addEventHandler(MouseEvent.MOUSE_EXITED, (e) -> centerButton.setOpacity(0));

		rightButton.addEventHandler(MouseEvent.MOUSE_ENTERED, (e) -> rightButton.setOpacity(0.4));
		rightButton.addEventHandler(MouseEvent.MOUSE_EXITED, (e) -> rightButton.setOpacity(0));
		rightButton.addEventHandler(MouseEvent.MOUSE_CLICKED, (e) -> {
			if (e.getButton().equals(MouseButton.PRIMARY)) {
				playNextSong();
			}
		});

		leftButton.addEventHandler(MouseEvent.MOUSE_ENTERED, (e) -> leftButton.setOpacity(0.4));
		leftButton.addEventHandler(MouseEvent.MOUSE_EXITED, (e) -> leftButton.setOpacity(0));
		leftButton.addEventHandler(MouseEvent.MOUSE_CLICKED, (e) -> {
			if (e.getButton().equals(MouseButton.PRIMARY)) {
				playPrevSong();
			}
		});
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

		scene.setOnKeyPressed((e) -> {
			switch (e.getCode()) {
				case ALT:
					altDown = true;
					if (looping) {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/loop.png")));
					} else {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/noloop.png")));
					}
					break;
				case SHIFT:
					shiftDown = true;
					break;
				case CONTROL:
					ctrlDown = true;
					break;
			}
		});

		scene.setOnKeyReleased((e) -> {
			switch (e.getCode()) {
				case ALT:
					altDown = false;
					if (paused) {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/play.png")));
					} else {
						centerButton.setImage(new Image(getClass().getResourceAsStream("resources/pause.png")));
					}
					break;
				case SHIFT:
					shiftDown = false;
					break;
				case CONTROL:
					ctrlDown = false;
					break;
			}
		});

		mainStage.focusedProperty().addListener((value, t, t1) -> {
			if (t1) {
				altDown = false;
				if (paused) {
					centerButton.setImage(new Image(getClass().getResourceAsStream("resources/play.png")));
				} else {
					centerButton.setImage(new Image(getClass().getResourceAsStream("resources/pause.png")));
				}
			}
		});

		//move to center
		Rectangle2D screenBounds = Screen.getPrimary().getBounds();
		centerX = (float) (screenBounds.getWidth() / 2);
		centerY = (float) (screenBounds.getHeight() / 2);
		mainStage.setX(centerX - scene.getWidth() / 2);
		mainStage.setY(centerY - scene.getHeight() / 2);

		readConfig();
		Path path = new File(audioFolderPath).toPath();
		audioDirWatcher = path.getFileSystem().newWatchService();
		path.register(audioDirWatcher, ENTRY_DELETE);

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
				long nano = System.nanoTime();
				int search = -1;
				for (int i = 0; i < audioList.size(); i++) {
					String name = audioList.get(i).strA;
					System.out.println(name);
					if (name.startsWith(term)) {
						search = i;
						break; // audioList is sorted by length, so stop at the first find
					}
					// prioritize prefix searching - don't break if name merely contains term
					if (search == -1 && name.contains(term)) {
						search = i;
					}
				}
				System.out.println("Time: " + (System.nanoTime() - nano));
				if (search >= 0) {
					playSong(search, true);
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
			trayIcon = new TrayIcon(iconImg, "Radium", popup);
			try {
				tray.add(trayIcon);
			} catch (AWTException e) {
				e.printStackTrace();
			}
		}
	}

	private void playPrevSong() {
		if (!playedSongs.isEmpty()) {
			playSong(playedSongs.remove(playedSongs.size() - 1), false);
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
				// these will be handled per audio spectrum update
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
			dMagnitudes[i] = Math.max((magnitudes[i] + 60) * 3 + 10, dMagnitudes[i] * 0.96);
			xs[i] = magnitudeIncrement * i;
		}
		for (int i = 0, l = (bandCount - adjustedSize); i < l; i++) {
			dMagnitudes[i + adjustedSize] = dMagnitudes[i];
			xs[i + adjustedSize] = 1 + (magnitudeIncrement * i);
		}

		PolynomialSplineFunction spline = interpolator.interpolate(xs, dMagnitudes);
		double avgLoudness = 0;
		for (int i = 0; i < barCount; i++) {
			double mag = spline.value(barIncrement * i);
			double rads = i * radianIncrement;
			double sin = Math.sin(rads);
			double cos = Math.cos(rads);
			double len = 200 + mag;
			float alpha = (float) TMath.clamp(mag / 100, 0, 1);
			avgLoudness += alpha;
			if (barColorer == null) {
				if (barColorMode.equals(ColorSourceMode.PALETTE_ALL)) {
					if (palette != null && !palette.isEmpty()) {
						Pair<Color, Integer> pair = palette.get(i % palette.size());
						Color c = pair.getKey();
						if (useDynamicBarOpacity) {
							audioVizContext.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
						} else {
							audioVizContext.setStroke(Color.color(c.getRed(), c.getGreen(), c.getBlue(), 0.8));
						}
					}
				} else { // only 1 color is used for all bars
					if (useDynamicBarOpacity) {
						if (barColorMode.equals(ColorSourceMode.PALETTE_FIRST)) {
							audioVizContext.setStroke(TColorsFX.setAlpha(palette.get(0).getKey(), alpha));
						} else {
							//color default mode
							audioVizContext.setStroke(TColorsFX.setAlpha(defaultBarColor, alpha));
						}
					}
				}
			} else { // barColorer not null
				audioVizContext.setStroke(barColorer.getBar(i, alpha));
			}
			audioVizContext.strokeLine(canvasCenterX + 190 * sin, canvasCenterY + 190 * cos, canvasCenterX + len * sin, canvasCenterY + len * cos);
		}
		if (barColorer != null) {
			avgLoudness /= barCount;
			circle.setStroke(barColorer.getBase((float) avgLoudness));
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

	private void playSong(int index, boolean affectStack) {
		prevSongIndex = currentSongIndex;
		if (affectStack) {
			if (playedSongs.size() >= songStackSize) {
				playedSongs.remove(0);
			}
			playedSongs.add(prevSongIndex);
		}
//		addPrevToStack = affectStack;
		currentSongIndex = index;

		song = null;
		if (mediaPlayer != null) {
			mediaPlayer.stop();
			mediaPlayer.dispose();
			mediaPlayer.setAudioSpectrumListener(null);
			mediaPlayer = null;
		}


		checkSongFolder();

		song = new Media(audioFolderUrl + audioList.get(index).strB);
		mediaPlayer = new MediaPlayer(song);
		mediaPlayer.setAudioSpectrumNumBands(bandCount * 2);
		mediaPlayer.setAudioSpectrumListener(this);
		mediaPlayer.setAudioSpectrumInterval(updateInterval);
		mediaPlayer.setOnEndOfMedia(() -> {
			if (looping) {
				playSong(index, false);
			} else {
				playNextSong();
			}
		});

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
			String fullUrl = imageFolderUrl + imgUrl;
			if (imgUrl.charAt(imgUrl.length() - 1) == '/') {
				print("Picking random image from folder");
				//imgUrl leads to folder, pick random image from folder
				File[] images = new File(urlToFile(fullUrl.substring(6))).listFiles();
				if (images != null) {
					do {
						File selected = images[random.nextInt(images.length)];
						if (selected.isDirectory()) {
							images = selected.listFiles();
							if (images == null) {
								break;
							}
						} else {
							try {
								image = new Image(selected.toURI().toURL().toString(), 350, 350, true, false);
							} catch (MalformedURLException e) {
								e.printStackTrace();
							}
						}
					} while (image == null);
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

		prevSongIndex = index;
	}

	private void checkSongFolder() {
		WatchKey fileChange = audioDirWatcher.poll();
		if (fileChange != null) {
			for (WatchEvent e : fileChange.pollEvents()) {
				System.out.println(e.context());
				if (acceptedAudioTypes.contains(getExtension(e.context().toString()))) {
					String[] songInfo = getSongInfo(e.context().toString());
					String simplifiedName = simplifySongName(songInfo[0]);

					if (e.kind().equals(ENTRY_DELETE)) {
						for (int i = 0; i < audioList.size(); i++) {
							if (audioList.get(i).strA.equals(simplifiedName)) {
								audioList.remove(i);
								// try to remove deleted song from stack
								playedSongs.remove(i);
								// repair indices of songs in stack
								for (int i2 = 0; i2 < playedSongs.size(); i2++) {
									if (playedSongs.get(i2) > i) {
										playedSongs.set(i2, i2 - 1);
									}
								}
								print("Song " + songInfo[0] + " has been removed from the audio directory");
								break;
							}
						}
					} else if (e.kind().equals(ENTRY_CREATE)) {
						// CANNOT CURRENTLY HANDLE NEW FILES
					} else if (e.kind().equals(ENTRY_MODIFY)) {
						// CANNOT CURRENTLY FILE MODIFICATION
					}
				}
			}
			fileChange.reset();
		}
	}

	private void playNextSong() {
		int next = random.nextInt(audioList.size());
		if (currentSongIndex == next || playedSongs.contains(next)) {
			int currentStackSize = playedSongs.size();

			if (audioList.size() > (currentStackSize * 2)) {
				// if the stack is relatively short, keep guessing randomly until an unplayed song is found
				do {
					next = (short) random.nextInt(audioList.size());
				} while (playedSongs.contains(next));
			} else if (audioList.size() > currentStackSize) {
				// if the stack is relatively long, search linearly until an unplayed song is found
				do {
					next++;
				} while (playedSongs.contains(next));
			} else if (currentStackSize >= 2) {
				// if the stack is longer than or equal in size to the list of songs, give up and play one from the least recent half
				next = playedSongs.get(random.nextInt(currentStackSize) / 2);
			}

		}
		playSong(next, true);
	}

	private void recursiveSongGrab(File directory, List<StringPair> list) {
		//noinspection ConstantConditions
		for (File file : directory.listFiles()) {
			if (file.isDirectory()) {
				recursiveSongGrab(file, list);
			} else {
				if (acceptedAudioTypes.contains(getExtension(file.getName()))) {
					list.add(new StringPair(simplifySongName(file.getName()), file.toURI().toString().substring(this.audioFolderUrl.length())));
				}
			}
		}
	}

	private void findSongs() {
		audioList.clear();
		recursiveSongGrab(new File(audioFolderPath), audioList);
		audioList.sort(Comparator.comparingInt(o -> o.strA.length()));
		print(audioList.size() + " audio clips found");
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
			audioFolderUrl = fileToUrl(audioFolderPath);
			print("Audio source folder set to " + audioFolderPath);

			String imageFolderPath = config.get("ImageFolder");
			if (imageFolderPath.charAt(0) == '~') {
				imageFolderPath = directoryFile + imageFolderPath.substring(1);
			}
			this.imageFolderPath = imageFolderPath;
			imageFolderUrl = fileToUrl(imageFolderPath);

			print("Grabbing images from " + imageFolderPath);
			songStackSize = Integer.parseInt(config.getOrDefault("StackSize", "5"));
			playedSongs = new ArrayList<>(songStackSize);
			setDefaultBaseColor(Color.web(config.getOrDefault("BaseColor", "#F8F8F8")));
			setDefaultBarColor(Color.web(config.getOrDefault("BarColor", "#FFFFFFCC")));
			setUseDynamicBarOpacity(config.getOrDefault("DynamicOpacity", "true").equals("true"));
			setColorDiffThreshold(Double.parseDouble(config.getOrDefault("ColorDiffThreshold", "0.4")));
			setColorFreqThreshold(Double.parseDouble(config.getOrDefault("ColorFreqThreshold", "4")));
			setBarCount(Integer.parseInt(config.getOrDefault("BarCount", "128")));
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
		for (char c : decode(s.substring(Math.max(0, s.lastIndexOf('/')))).toCharArray()) {
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
			if (c == '@') {
				break;
			}
			if ((c >= 'a' && c <= 'z') ||
				(c >= '0' && c <= '9')) {
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
		if (audioDirWatcher != null) {
			try {
				audioDirWatcher.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
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
								indexA < 0 ? null : src.substring(0, indexA)};
		} else {
			return new String[]{src.substring(indexA + 1, indexC),
								null,
								indexA < 0 ? null : src.substring(0, indexA)};
		}
	}

	private void updateColorPalette() {
		palette = Collections.unmodifiableList(TColorsFX.getColorPaletteFast(imageView.getImage(), colorDiffThreshold));

		if (palette.isEmpty()) {
			palette = Collections.singletonList(new Pair<>(defaultBaseColor, 1));
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
			palette = Collections.unmodifiableList(palette.subList(0, Math.max(2, i - (barCount % i))));
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
		int cropRadius = Math.min(imgWidth - 2 * mx, imgHeight - 2 * my) - 20;

		return new WritableImage(reader, (imgWidth - cropRadius) / 2, (imgHeight - cropRadius) / 2, cropRadius, cropRadius);
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
				playNextSong();
			}
			if (!paused) {
				mediaPlayer.play();
			}
		}
	}
}