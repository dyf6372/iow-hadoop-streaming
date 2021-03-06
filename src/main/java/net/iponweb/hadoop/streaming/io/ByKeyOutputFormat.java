/**
 * Copyright 2014 IPONWEB
 *
 * Licensed under the Apache License, Textersion 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY TextIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.iponweb.hadoop.streaming.io;

import net.iponweb.hadoop.streaming.tools.KeyValueSplitter;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.*;
import org.apache.hadoop.util.Progressable;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;


/* OutputFormat with special ability. Everything before first TAB would become a
 * path name into which that record would be placed. Great for creating several
 * semantically different outputs from single job.
 * If outputting records from several reducers, add ReducerID to the end of
 * the path, otherwise file could become corrupted.
 *
 * Let reducer outputs following lines (assuming separator is <TAB>):
 *
 * typeA/0<TAB>rest-of-the-record
 * typeA/1<TAB>rest-of-the-record
 * ...
 * typeB/0<TAB>rest-of-the-record
 *
 * After that you will have following file in you job output directory:
 *
 * typeA/0
 * typeA/1
 * ...
 * typeB/0
 *
 * Please note, that real outputformat should be indicated as -D iow.streaming.bykeyoutputformat=<format>
 * Supported formats are:
 *   text
 *   sequence
 *   avrotext (job output is text which is converted to Avro; See AvroAsTextOutputFormat)
 *   avrojson (job output is json which is converted to Avro; See AvroAsJsonOutputFormat)
 *   parquettext (job output is text which is converted to Parquet; See ParquetAsTextOutputFormat)
 *   parquetjson (job output is json which is converted to Parquet; See ParquetAsJsonOutputFormat)
 *
 * In case of non-text formats, different schemas are supported. They should prefix output file and
 * should be delimited by colon
 *
 * schemaA:typeA/0<TAB>...
 */

public class ByKeyOutputFormat extends FileOutputFormat<Text, Text> {
    private static final Log LOG = LogFactory.getLog(net.iponweb.hadoop.streaming.io.ByKeyOutputFormat.class);
    private OutputFormat<Text, Text> internalOutputFormat;
    private KeyValueSplitter splitter;
    private boolean assumeFileNamesSorted;
    private HashMap<String,String> SupportedOutputFormats = new HashMap<String,String>();


    private void initialize(JobConf job) throws IOException {

        SupportedOutputFormats.put("text", "org.apache.hadoop.mapred.TextOutputFormat");
        SupportedOutputFormats.put("sequence", "org.apache.hadoop.mapred.SequenceFileOutputFormat");
        SupportedOutputFormats.put("avrojson", "net.iponweb.hadoop.streaming.avro.AvroAsJsonOutputFormat");
        SupportedOutputFormats.put("avrotext", "net.iponweb.hadoop.streaming.avro.AvroAsTextOutputFormat");
        SupportedOutputFormats.put("parquettext", "net.iponweb.hadoop.streaming.parquet.ParquetAsTextOutputFormat");
        SupportedOutputFormats.put("parquetjson", "net.iponweb.hadoop.streaming.parquet.ParquetAsTextOutputFormat");

        String format = job.get("iow.streaming.bykeyoutputformat", "text");
        for (String f : SupportedOutputFormats.keySet())
            if (f.equals(format)) {

                try {
                    internalOutputFormat =  (OutputFormat<Text,Text>)
                        Class.forName(SupportedOutputFormats.get(f)).newInstance();
                } catch (Exception e) {
                    e.printStackTrace();
                    throw new IOException("Can't instantiate class '" + SupportedOutputFormats.get(f) + "'");
                }
            }

        if (internalOutputFormat == null)
            throw new IOException("Unknown result type: '" + format + "'");

        assumeFileNamesSorted = job.getBoolean("iow.streaming.bykeyoutputformat.keys.sorted", false);
        String delimiter = job.get("map.output.key.field.separator", "\t");
        splitter = new KeyValueSplitter(delimiter);

        LOG.info(getClass().getSimpleName() + " initialized, output format is: " + format);
    }

    @Override
    public RecordWriter<Text, Text> getRecordWriter(final FileSystem fs, final JobConf job, String name, final Progressable progressable) throws IOException {
        initialize(job);
        return new RecordWriter<Text, Text>() {
            private RecordWriter<Text, Text> currentWriter;
            private String currentTextey;
            private TreeMap<String, RecordWriter<Text, Text>> recordWriterByTexteys = new TreeMap<String, RecordWriter<Text, Text>>();

            @Override
            public void write(Text key, Text value) throws IOException {
                String fileName = generateFileNameForTexteyTextalue(key, value);
                if (assumeFileNamesSorted) {
                    if (!fileName.equals(currentTextey)) {
                        if (currentWriter != null) {
                            currentWriter.close(Reporter.NULL);
                        }
                        currentWriter = getBaseRecordWriter(fs, job, fileName, progressable);
                        currentTextey = fileName;
                    }
                    currentWriter.write(key, value);
                } else {
                    RecordWriter<Text, Text> writer = recordWriterByTexteys.get(fileName);
                    if (writer == null) {
                        writer = getBaseRecordWriter(fs, job, fileName, progressable);
                        recordWriterByTexteys.put(fileName, writer);
                    }
                    writer.write(key, value);
                }
                progressable.progress();
            }

            @Override
            public void close(Reporter reporter) throws IOException {
                if (currentWriter != null) {
                    currentWriter.close(reporter);
                }
                for (RecordWriter<Text, Text> writer : recordWriterByTexteys.values()) {
                    writer.close(reporter);
                }
            }
        };
    }

    protected RecordWriter<Text, Text> getBaseRecordWriter(FileSystem fileSystem, JobConf jobConf, String name, Progressable progressable) throws IOException {
        if (name == null || name.isEmpty()) {
            throw new IOException("Invalid name: " + name);
        }
        final RecordWriter<Text, Text> internalWriter = internalOutputFormat.getRecordWriter(fileSystem, jobConf, name, progressable);
        if (internalWriter == null) {
            throw new IllegalStateException("Internal format returned null record writer. Format=" + internalOutputFormat);
        }
        return new RecordWriter<Text, Text>() {
            @Override
            public void write(Text key, Text value) throws IOException {
                Map.Entry<String, String> keyvalue = splitter.split(value.toString());
                internalWriter.write(new Text(keyvalue.getKey()), new Text(keyvalue.getValue()));
            }

            @Override
            public void close(Reporter reporter) throws IOException {
                internalWriter.close(reporter);
            }
        };
    }

    protected String generateFileNameForTexteyTextalue(Text key, Text value) {
        String keyStr = key.toString();
        Map.Entry<String, String> split = splitter.split(keyStr);
        return split.getKey();
    }
}
