package com.test.jacoco.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;

import org.jacoco.core.data.ExecutionData;
import org.jacoco.core.data.IExecutionDataVisitor;
import org.jacoco.core.data.ISessionInfoVisitor;
import org.jacoco.core.data.SessionInfo;
import org.jacoco.core.runtime.RemoteControlReader;
import org.jacoco.core.runtime.RemoteControlWriter;

public class ExecutionDataServer {
	private static String jacocoData = "jacoco.exec";
	private static String filePath = "/Volumes/Data/cjq/jacocodata/exec/";
	private static final String ADDRESS = "localhost";
//	private static final String configPath = "/Volumes/Data/cjq/jacocodata/jacococonfig/config.prop";
	private static final int PORT = 6300;
	private static final int INTERVAL_DUMP_SEC = 14400;
	private static String currentDate;
//    private static RemoteControlWriter fileWriter;
	private static List<Handler> listHandler;

//  Start the server as a standalone program.

	public static void main(final String[] args) throws IOException, ParseException {

		System.out.println("Starting TCP server on: " + ADDRESS + ":" + PORT);
		final ServerSocket server = new ServerSocket(PORT, 0, InetAddress.getByName(ADDRESS));
		listHandler = new ArrayList<Handler>();

		// thread to collect dump data at specified INTERVAL_DUMP_SEC
		Runnable runDumpTimer = new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						int interval = 10;
						if (interval != -1) {
							Thread.sleep(interval * 1000);
						} else {
							Thread.sleep(INTERVAL_DUMP_SEC * 1000);
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					String cmdstr = null;
					
					Iterator<Handler> it = listHandler.iterator();
					if (it.hasNext()) {
						System.out.println("******************输入指令*********************");
						Scanner sc = new Scanner(System.in);
						System.out.println("如果需要dump数据，请输入命令：dump");
						System.out.println("如果需要reset数据，请输入命令：reset");
						cmdstr = sc.next();
					}
					synchronized (listHandler) {
						Iterator<Handler> it2 = listHandler.iterator();
						while (it2.hasNext()) {
							ExecutionDataServer.Handler handler = (ExecutionDataServer.Handler) it2.next();
							boolean toRemove = false;
							String id = handler.getId();
							try {
								if (!handler.isAlive()) {
									System.out.println("Socket closed, removing handler: " + id);
									toRemove = true;
								} else {
									if (cmdstr.equals("dump")) {
										System.out.println("Sending dump command to " + id);
										handler.captureDump(true, false);
									} else if (cmdstr.equals("reset")) {
										System.out.println("Sending reset command to " + id);
										handler.captureDump(false, true);
									} else {
										System.out.println("不支持的命令！");
										break;
									}
								}
							} catch (IOException e) {
								System.out.println("Socket error: " + e.getMessage() + ", removing handler: " + id);
								toRemove = true;
							} finally {
								if (toRemove) {
									it2.remove();
								}
							}
						}
					}
//					Iterator<Handler> it = listHandler.iterator();
//					if (it.hasNext()) {
//						System.out.println("******************输入指令*********************");
//						Scanner sc = new Scanner(System.in);
//						System.out.println("如果需要dump数据，请输入命令：dump");
//						System.out.println("如果需要reset数据，请输入命令：reset");
//						cmdstr = sc.next();
//					}
//					while (it.hasNext()) {
//						ExecutionDataServer.Handler handler = (ExecutionDataServer.Handler) it.next();
//						boolean toRemove = false;
//						String id = handler.getId();
//						try {
//							if (!handler.isAlive()) {
//								System.out.println("Socket closed, removing handler: " + id);
//								toRemove = true;
//							} else {
//								if (cmdstr.equals("dump")) {
//									System.out.println("Sending dump command to " + id);
//									handler.captureDump(true, false);
//								} else if (cmdstr.equals("reset")) {
//									System.out.println("Sending reset command to " + id);
//									handler.captureDump(false, true);
//								} else {
//									System.out.println("不支持的命令！");
//									break;
//								}
//							}
//						} catch (IOException e) {
//							System.out.println("Socket error: " + e.getMessage() + ", removing handler: " + id);
//							toRemove = true;
//						} finally {
//							if (toRemove) {
//								it.remove();
//							}
//						}
//					}
				}
			}
		};

		Thread threadDumpTimer = new Thread(runDumpTimer, "threadDumpTimer");
		threadDumpTimer.start();

		while (true) {
			System.out.println("waiting for connection from client");
			Socket socket = server.accept();
			System.out.println("Remote connection detected, openning socket on local port: " + socket.getLocalPort());
			System.out.println("Remote Socket Address : " + socket.getRemoteSocketAddress().toString());

			currentDate = getCurrentDateFormatted("yyyyMMdd_hh_mm_ss_");

			// 获取已连接的远程client地址;
			String remoteSocketAddress = socket.getRemoteSocketAddress().toString().replace('/', ' ').trim();
			int index = remoteSocketAddress.indexOf(":");
			remoteSocketAddress = remoteSocketAddress.substring(0, index);

			String jacocoDataFilePath = filePath + currentDate + remoteSocketAddress + "_" + jacocoData;

			System.out.println("Output file is: " + new File(jacocoDataFilePath).getAbsolutePath());

			RemoteControlWriter fileWriter = new RemoteControlWriter(new FileOutputStream(jacocoDataFilePath));

			final Handler handler = new Handler(socket, fileWriter, jacocoDataFilePath);
			listHandler.add(handler);
			new Thread(handler).start();
		}
	}

	private static class Handler implements Runnable, ISessionInfoVisitor, IExecutionDataVisitor {
		private final Socket socket;
		private final RemoteControlReader reader;
		private RemoteControlWriter fileWriter;
		private final RemoteControlWriter remoteWriter;
		private String id;
		private String jacocoDataFilePath;

		public String getId() {
			return id;
		}

		Handler(final Socket socket, final RemoteControlWriter fileWriter, final String jacocoDataFilePath) throws IOException {
			this.socket = socket;
			this.fileWriter = fileWriter;
			this.jacocoDataFilePath = jacocoDataFilePath;
			// Just send a valid header:
			remoteWriter = new RemoteControlWriter(socket.getOutputStream());
			reader = new RemoteControlReader(socket.getInputStream());
			reader.setSessionInfoVisitor(this);
			reader.setExecutionDataVisitor(this);
			captureDump(true,true);
		}

		public void run() {
			try {

				while (reader.read()) {

				}

				socket.close();

				synchronized (fileWriter) {
					fileWriter.flush();
					deleteJacocoFile(jacocoDataFilePath);
				}
			} catch (final IOException e) {
				e.printStackTrace();
			}
		}

		public void visitSessionInfo(final SessionInfo info) {
			id = info.getId();
			System.out.printf("Retrieving execution Data for session: %s%n", info.getId());
			synchronized (fileWriter) {
				fileWriter.visitSessionInfo(info);
			}
		}

		public void visitClassExecution(final ExecutionData data) {
			synchronized (fileWriter) {
				fileWriter.visitClassExecution(data);
			}
		}

		public void captureDump(boolean dump, boolean reset) throws IOException {
			remoteWriter.visitDumpCommand(dump, reset);
		}

		public boolean isAlive() {
			if (socket != null && socket.isConnected()) {
				return true;
			}
			return false;
		}
		
		public void deleteJacocoFile(String JacocoDataFilePath) {
			File folder = new File(filePath);
			File[] files = folder.listFiles();
			for(File file:files) {
				if(file.getAbsolutePath().equals(JacocoDataFilePath)) {
					file.delete();
				}
			}
		}


	}

	public static String getCurrentDateFormatted(String dateFormat) {
		SimpleDateFormat dateFormatter;
		dateFormatter = new SimpleDateFormat(dateFormat);
		Date currentDate = new Date();
		return dateFormatter.format(currentDate);
	}

/**
	private static final class ConfigurationHandler {
//		private File config = new File("/export/home/jacoco/config.prop");
		// configPath
		private File config = new File(configPath);

		private ConfigurationHandler() {

		}

		private int getData() {
			try {
				BufferedReader br = new BufferedReader(new FileReader(config));
				String data = br.readLine();
				int retVal = -1;
				if (data != null) {
					retVal = Integer.parseInt(data);
					System.out.println("Time interval is set to : " + retVal);
				} else {
					br.close();
					throw new RuntimeException("the configuration file data doesn't exist!");
				}
				br.close();
				return retVal;
			} catch (FileNotFoundException e) {
				e.printStackTrace();
				throw new RuntimeException("the configuration file  doesn't exist!");
			} catch (IOException e) {
				e.printStackTrace();
				throw new RuntimeException("the configuration file cannot be read!");
			}
		}
	}
**/
}