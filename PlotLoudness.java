import java.io.File;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.chart.XYChart;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.collections.ObservableList;
import javafx.collections.FXCollections;

import jp.ac.kyoto_u.kuis.le4music.Le4MusicUtils;

import java.io.IOException;
import javax.sound.sampled.UnsupportedAudioFileException;

public final class PlotLoudness extends Application {
    @Override public final void start(final Stage primaryStage)
        throws IOException,
               UnsupportedAudioFileException {
        /* コマンドライン引数処理 */
        final String[] args = getParameters().getRaw().toArray(new String[0]);
        if(args.length < 1){
            System.out.println("WAVFILE is not given.");
            Platform.exit();
            return;
        }
        final File wavFile = new File(args[0]);
        
        final double frameDuration = Le4MusicUtils.frameDuration;   // 0.2
        final double shiftDuration = frameDuration / 8.0;           // 0.025
        
        /* ファイル読み込みWAV */
        final AudioInputStream stream = AudioSystem.getAudioInputStream(wavFile);
        final double[] waveform = Le4MusicUtils.readWaveformMonaural(stream);   // 160000 in 10sec
        final AudioFormat format = stream.getFormat();
        final double sampleRate = format.getSampleRate();                       // 16000
        stream.close();
        
        /* 1フレームとシフトのサンプル数 */
        final int frameSize = (int)Math.round(frameDuration * sampleRate);  // 3200 per sec
        final int shiftSize = (int)Math.round(shiftDuration * sampleRate);  // 400 per sec
        
        /* 音量計算 */
        double[] vol = new double[(int)Math.round((waveform.length - frameSize) / shiftSize)];
        for(int i = 0; i < vol.length; i++){
            vol[i] = 0;
            for(int j = 0; j < frameSize; j++){
                vol[i] += Math.pow(waveform[i * shiftSize + j], 2.0);
            }
            vol[i] = vol[i] / waveform.length;
            vol[i] = 20 * Math.log10(Math.pow(vol[i], 0.5));
        }
        
        /* データ系列を作成 */
        final ObservableList<XYChart.Data<Number, Number>> data =
                IntStream.range(0, vol.length)
                    .mapToObj(i -> new XYChart.Data<Number, Number>(i / sampleRate * shiftSize, vol[i]))
                    .collect(Collectors.toCollection(FXCollections::observableArrayList));
        
        /* データ系列に名前をつける */
        final XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName("Volume");
        series.setData(data);
        
        /* 軸を作成 */
        final NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel("Time (seconds)");
        final NumberAxis yAxis = new NumberAxis();
        yAxis.setLabel("Amplitude (dB)");
        
        /* チャートを作成 */
        final LineChart<Number, Number> chart = new LineChart<>(xAxis, yAxis);
        chart.setTitle("Volume");
        chart.setCreateSymbols(false);
        chart.getData().add(series);
        
        /* グラフ描画 */
        final Scene scene = new Scene(chart, 800, 600);
        
        /* ウィンドウ表示 */
        primaryStage.setScene(scene);
        primaryStage.setTitle(getClass().getName());
        primaryStage.show();
    }
}