package com.msa.center;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;

import com.msa.extreme.ExtremeMSA;
import com.msa.utils.ClearDfsPath;
import com.msa.utils.CopyFile;

public class MatrixMSA {
    private String filepath = "";	//Log file name
	private static String Pi[]; 	//Record each sequence
	private static String Piname[];	//Record the name of each sequence
    private int Spaceevery[][];		//The position of the added space when the center sequence is aligned with the other sequences, respectively
    private int Spaceother[][];		//The position of the added space when storing other sequences aligned with the center sequence
    private int n;					//The number of stored sequences
    private int center;				//Stores the center sequence number
    private int spacescore = -1, matchscore = 0, mismatchscore = -1; //Define match, mismatch, and space penalty

    //The original star alignment algorithm
    public void start(String inputfile, String outputfile, String outputDFS) 
			throws IOException, ClassNotFoundException, InterruptedException {
        filepath = inputfile;
        n = countnum(); //Record the number of sequences
        if (outputDFS == null) 
        {
        	input();    //Pi PiName
		} else 
		{
			Pi = new String[n];
			Piname = new String[n];
			System.out.println(">>Clearing HDFS Path & uploading ...");
			new ClearDfsPath().run(outputDFS);
			CopyFile copyFile = new CopyFile();
			copyFile.local_to_dfs(inputfile, outputDFS + "/input/input.txt");
			
			System.out.println(">>Map reducing ...");
			Configuration conf = new Configuration();
			conf.set("mapred.task.timeout", "0");
			Job job = new Job(conf, "msa_extreme");
			job.setJarByClass(ExtremeMSA.class);
			job.setInputFormatClass(TextInputFormat.class);
			job.setMapperClass(MatrixMapper.class);
			job.setMapOutputKeyClass(NullWritable.class);
			job.setMapOutputValueClass(Text.class);
			FileInputFormat.addInputPath(job, new Path(outputDFS + "/input/input.txt"));
			FileOutputFormat.setOutputPath(job, new Path(outputDFS + "/output"));
			job.setNumReduceTasks(1);
			job.waitForCompletion(true);
		}
        
        
        // First look for the star sequence (the sequence with the largest similarity value), 
        // and then according to the star sequence for multiple sequence alignment.
        center = findNumMax(computesim());
        //The position of the added space when the center sequence is aligned with the other sequences, respectively
        Spaceevery = new int[n][Pi[center].length() + 1];
        //The position of the added space when storing other sequences aligned with the center sequence
        Spaceother = new int[n][computeMaxLength(center) + 1];
        for (int i = 0; i < n; i++) 
        {
            if (i == center) continue;
            int M[][] = computeScoreMatrixForDynamicProgram(Pi[i], Pi[center]);//The dynamic programming matrix is calculated
            //The inserted space is stored in the array, the space is stored in the center sequence, and the space is stored in the sequence
            traceBackForDynamicProgram(M, Pi[i].length(), Pi[center].length(), i, 0, 0);
        }
        //Space array length is the length of the central sequence +1, the elements deposited in the elements of the max spaceevery element value 
        //(that is, the central sequence should be inserted element value)
        int Space[] = combine();
        //Space
        output(Space, outputfile);
    }

    //Calculate the number of sequences
    private int countnum() {
        int num = 0;
        try 
        {
            BufferedReader br = new BufferedReader(new FileReader(filepath));
            String s;
            while (br.ready()) 
            {
                s = br.readLine();
                if (s.charAt(0) == '>')
                {
                    num++;
                }
            }
            br.close();
        } catch (Exception ignored) 
        {
        	
        }
        return (num);
    }

    //The sequence is read into the array once
    private void input() {
        Pi = new String[n];
        Piname = new String[n];
        int i = 0;
        try 
        {
            BufferedReader br = new BufferedReader(new FileReader(filepath));
            String BR = br.readLine();
            while (br.ready()) 
            {
                if (BR.length() != 0 && BR.charAt(0) == '>') {
                    Piname[i] = BR;
                    Pi[i] = "";
                    while (br.ready() && (BR = br.readLine()).charAt(0) != '>') 
                    {
                        Pi[i] += BR;
                    }
                    i++;
                } else
                    BR = br.readLine();
            }
            br.close();
        } catch (Exception ex) 
        {
            System.out.println(ex.getMessage());
            System.exit(0);
        }
    }

    //Find the largest row in the Num[][] array
    private int findNumMax(int Num[][]) {
        int Numsum[] = new int[n];
        for (int i = 0; i < n; i++) 
        {
            Numsum[i] = 0;
            for (int j = 0; j < n; j++)
            {
                Numsum[i] = Numsum[i] + Num[i][j];
            }
        }
        int tmpcenter = 0;
        for (int i = 1; i < n; i++) {
            if (Numsum[i] > Numsum[tmpcenter])
            {
                tmpcenter = i;
            }
        }
        return (tmpcenter);
    }

    //Matrix integration is calculated in dynamic programming
    private int[][] computeScoreMatrixForDynamicProgram(String stri, String strC) {
        int len1 = stri.length() + 1;
        int len2 = strC.length() + 1;
        int M[][] = new int[len1][len2];   //Define the dynamic programming matrix
        //Initializes the dynamic programming matrix
        int p, q;
        for (p = 0; p < len1; p++)
        {
            M[p][0] = spacescore * p;
        }
        for (q = 0; q < len2; q++)
        {
            M[0][q] = spacescore * q;
        }
        //Initialization ends
        //Calculate the value of the matrix
        for (p = 1; p < len1; p++) 
        {
            for (q = 1; q < len2; q++) 
            {
            	//M[p][q]=max(M[p-1][q]-1,M[p][q-1]-1,M[p-1][q-1]+h)
                int h;
                if (stri.charAt(p - 1) == strC.charAt(q - 1)) 
                {
                    h = matchscore;
                }
                else h = mismatchscore;
                M[p][q] = Math.max(M[p - 1][q - 1] + h, Math.max(M[p - 1][q] + spacescore, M[p][q - 1] + spacescore));
            }
        }
        return (M);
    }

    //Backtracking in Dynamic Programming
    private void traceBackForDynamicProgram(int[][] M, int p, int q, int i, int k1, int k2) {
        while (p > 0 && q > 0) {
            if (M[p][q] == M[p][q - 1] + spacescore) {
                Spaceother[i][p + k1]++;
                q--;
            } else if (M[p][q] == M[p - 1][q] + spacescore) {
                Spaceevery[i][q + k2]++;
                p--;
            } else {
                p--;
                q--;
            }
        }
        if (p == 0) {
            while (q > 0) {
                Spaceother[i][k1]++;
                q--;
            }
        }
        if (q == 0) {
            while (p > 0) {
                Spaceevery[i][k2]++;
                p--;
            }
        }
    }

    private int[] combine() {
        int Space[] = new int[Pi[center].length() + 1];
        int i, j;
        for (i = 0; i < Pi[center].length() + 1; i++) {
            int max = 0;
            for (j = 0; j < n; j++) {
                if (Spaceevery[j][i] > max) {
                    max = Spaceevery[j][i];
                }
            }
            Space[i] = max;
        }
        return (Space);
    }

    //The maximum length of the sequence other than the central sequence is calculated
    private int computeMaxLength(int center) {
        int maxlength = 0;
        for (int i = 0; i < n; i++) {
            if (i == center)
                continue;
            if (Pi[i].length() > maxlength)
                maxlength = Pi[i].length();
        }
        return (maxlength);
    }

    private void output(int[] Space, String outputfile) {
        int i, j;
        //Output center sequence
        String PiAlign[] = new String[n];
        PiAlign[center] = "";
        for (i = 0; i < Pi[center].length(); i++) {
            for (j = 0; j < Space[i]; j++) {
                PiAlign[center] = PiAlign[center].concat("-");
            }
            PiAlign[center] = PiAlign[center].concat(Pi[center].substring(i, i + 1));
        }
        for (j = 0; j < Space[Pi[center].length()]; j++) {
            PiAlign[center] = PiAlign[center].concat("-");
        }
        //Other sequences are output
        for (i = 0; i < n; i++) {
            if (i == center)
                continue;
            //P[i] after calculation and central sequence alignment is denoted by Pi
            PiAlign[i] = "";
            for (j = 0; j < Pi[i].length(); j++) {
                String kong = "";
                for (int k = 0; k < Spaceother[i][j]; k++) {
                    kong = kong.concat("-");
                }
                PiAlign[i] = PiAlign[i].concat(kong).concat(Pi[i].substring(j, j + 1));
            }
            String kong = "";
            for (j = 0; j < Spaceother[i][Pi[i].length()]; j++) {
                kong = kong.concat("-");
            }
            PiAlign[i] = PiAlign[i].concat(kong);
            //Pi calculation ends
            //Calculate the difference array
            int Cha[] = new int[Pi[center].length() + 1];
            int position = 0;    //Used to record the insertion of different spaces
            for (j = 0; j < Pi[center].length() + 1; j++) {
                Cha[j] = 0;
                if (Space[j] - Spaceevery[i][j] > 0) {
                    Cha[j] = Space[j] - Spaceevery[i][j];
                }
                //The difference array is calculated
                //Fill in the difference space
                position = position + Spaceevery[i][j];
                if (Cha[j] > 0) {  //Inserts Cha[j] spaces at position position
                    kong = "";
                    for (int k = 0; k < Cha[j]; k++) {
                        kong = kong.concat("-");
                    }
                    PiAlign[i] = PiAlign[i].substring(0, position).concat(kong).concat(PiAlign[i].substring(position));
                }
                position = position + Cha[j] + 1;
                //The difference space is filled in
            }
        }
        try {
            BufferedWriter bw = new BufferedWriter(new FileWriter(outputfile));
            for (i = 0; i < n; i++) {
                //System.out.println(Piname[i]);
                bw.write(Piname[i]);
                bw.newLine();
                bw.flush();
                //System.out.println(PiAlign[i]);
                bw.write(PiAlign[i]);
                bw.newLine();
                bw.flush();
            }
            bw.close();
        } catch (Exception ignored) {

        }
    }

    //Computes the value of the sim matrix in the original star alignment
    private int[][] computesim() {
        int[][] sim = new int[n][n];
        int i, j;
        //Calculate the upper triangle
        for (i = 0; i < n; i++) {
            for (j = i + 1; j < n; j++) {
                int M[][];
                // Dynamic Programming Contrast of Dual Sequences
                M = computeScoreMatrixForDynamicProgram(Pi[i], Pi[j]);
                sim[i][j] = M[Pi[i].length()][Pi[j].length()];
            }
        }
        //Calculate the lower triangle
        for (i = 0; i < n; i++)
            sim[i][i] = 0;
        for (i = 1; i < n; i++)
            for (j = 0; j < i; j++) {
                sim[i][j] = sim[j][i];
            }
        return (sim);
    }
    
	public static class MatrixMapper extends Mapper<Object, Text, NullWritable, Text> {
		int count = 0;
		public void map(Object key, Text value, Context context) throws IOException, InterruptedException{
			if (value.charAt(0) == '>') {
				Piname[count] = value.toString();
			} else {
				Pi[count] = value.toString();
				count++;
			}
			context.write(NullWritable.get(), value);
		}
	}
}
