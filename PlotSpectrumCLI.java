import java.lang.invoke.MethodHandles;
import java.io.File;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.imageio.ImageIO;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.image.WritableImage;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;
import javafx.embed.swing.SwingFXUtils;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.HelpFormatter;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;
import org.apache.commons.math3.complex.Complex;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.apache.commons.cli.ParseException;

public final class PlotSpectrumCLI extends Application {
    
    private static final Options options = new Options();
    private static final String helpMessage = 
            MethodHandles.lookup().lookupClass().getName() + " [OPTIONS] <WAVFILE>";

    static{
        // コマンドラインオプション定義
        options.addOption("h", "help", false, "Display this help and exit");
        options.addOption("o", "outfile", true, "Output image file (Default: " +
                                                 MethodHandles.lookup().lookupClass().getSimpleName() +
                                                 "." + Le4MusicUtils.outputImageExt + ")");
        options.addOption(null, "amp-lo", true,
                          "Lower bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
        options.addOption(null, "amp-up", true,
                          "Upper bound of amplitude [dB] (Default: " +
                          Le4MusicUtils.spectrumAmplitudeLowerBound + ")");
        options.addOption(null, "freq-lo", true,
                          "Lower bound of frequency [Hz] (Default: 0.0)");
        options.addOption(null, "freq-up", true,
                          "Upper bound of frequency [Hz] (Default: Nyquist)");
    }
    
    @Override public final void start(final Stage primaryStage)
	throws IOException,
	       UnsupportedAudioFileException,
               ParseException {
	/* コマンドライン引数処理 */
	final String[] args = getParameters().getRaw().toArray(new String[0]);
        final CommandLine cmd = new DefaultParser().parse(options, args);
        if(cmd.hasOption("help")){
            new HelpFormatter().printHelp(helpMessage, options);
            Platform.exit();
            return;
        }
        final String[] pargs = cmd.getArgs();
	if(pargs.length < 1){
	    System.out.println("WAVFILE is not given.");
            new HelpFormatter().printHelp(helpMessage, options);
	    Platform.exit();
	    return;
	}
	final File wavFile = new File(pargs[0]);

	/* WAVファイル読み込み */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();
        final double nyquist = sampleRate * 0.5;
        stream.close();
 
        /* fftSize = 2^p >= waveform.length を満たす fftSize を求める
         * 2^p はシフト演算で求める */
        final int fftSize = 1 << Le4MusicUtils.nextPow2(waveform.length);
        final int fftSize2 = (fftSize >> 1) + 1;
        /* 信号の長さを fftSize に伸ばし、長さが足りない部分は0で埋める。
         * 振幅を信号長で正規化する。 */
        final double[] src =
                Arrays.stream(Arrays.copyOf(waveform, fftSize))
                      .map(w -> w / waveform.length)
                      .toArray();
        /* 高速フーリエ変換を行う */
        final Complex[] spectrum = Le4MusicUtils.rfft(src);
        
        /* 対数振幅スペクトルを求める */
        final double[] specLog =
                Arrays.stream(spectrum)
                      .mapToDouble(c -> 20.0 * Math.log10(c.abs()))
                      .toArray();
        
        // スペクトル配列の各要素に対応する周波数を求める
        // 以下を満たすように線形に
        // freqs[0] = 0Hz
        // freqs[fftSize2 -1] = sampleRate / 2 (= Nyquist周波数)
        final double[] freqs =
                IntStream.range(0, fftSize2)
                         .mapToDouble(i -> i * sampleRate / fftSize)
                         .toArray();
        
        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, fftSize2)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i * sampleRate / fftSize, specLog[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>("Spectrum", data);
        
        /* x軸を作成 */
        final double freqLowerBound = 
                Optional.ofNullable(cmd.getOptionValue("freq-io"))
                        .map(Double::parseDouble)
                        .orElse(0.0);
        if(freqLowerBound < 0.0)
            throw new IllegalArgumentException(
                "freq-lo must be non-negative: " + freqLowerBound
            );
        final double freqUpperBound = 
                Optional.ofNullable(cmd.getOptionValue("freq-up"))
                        .map(Double::parseDouble)
                        .orElse(nyquist);
        if(freqUpperBound <= freqLowerBound)
            throw new IllegalArgumentException(
                "freq-up must be larger than freq-io: " +
                "freq-io = " + freqLowerBound + ", freq-up = " + freqUpperBound
            );
        final NumberAxis xAxis= new NumberAxis("Frequency (Hz)", freqLowerBound, freqUpperBound,
                                               Le4MusicUtils.autoTickUnit(freqUpperBound - freqLowerBound)
        );
        xAxis.setAnimated(false);
        
        /* y軸を作成 */
        final double ampLowerBound =
                Optional.ofNullable(cmd.getOptionValue("amp-lo"))
                        .map(Double::parseDouble)
                        .orElse(Le4MusicUtils.spectrumAmplitudeLowerBound);
        final double ampUpperBound =
                Optional.ofNullable(cmd.getOptionValue("amp-up"))
                        .map(Double::parseDouble)
                        .orElse(Le4MusicUtils.spectrumAmplitudeUpperBound);
        if(ampUpperBound <= ampLowerBound)
            throw new IllegalArgumentException(
                "amp-up must be larger than amp-lo: " +
                "amp-lo = " + ampLowerBound + ", amp-up = " + ampUpperBound
            );
        final NumberAxis yAxis = new NumberAxis("Amplitude (dB)", ampLowerBound, ampUpperBound,
                                                Le4MusicUtils.autoTickUnit(ampUpperBound - ampLowerBound)
        );
        yAxis.setAnimated(false);
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Spectrum");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        scene.getStylesheets().add("le4music.css");
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
}