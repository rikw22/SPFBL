/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package br.com.allchemistry.spf;

import br.com.allchemistry.core.Server;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.LinkedList;
import java.util.concurrent.Semaphore;

/**
 * Servidor de consulta em SPF.
 * 
 * Este serviço responde a consulta e finaliza a conexão logo em seguida.
 * 
 * @author Leandro Carlos Rodrigues <leandro@allchemistry.com.br>
 */
public final class QuerySPF extends Server {

    private final int PORT;
    private final ServerSocket SERVER_SOCKET;
    
    /**
     * Configuração e intanciamento do servidor.
     * @param port a porta SPF a ser vinculada.
     * @throws java.io.IOException se houver falha durante o bind.
     */
    public QuerySPF(int port) throws IOException {
        super("ServerSPF");
        PORT = port;
        // Criando conexões.
        Server.logDebug("Binding SPF socket on port " + port + "...");
        SERVER_SOCKET = new ServerSocket(port);
    }
    
    /**
     * Representa uma conexão ativa.
     * Serve para processar todas as requisições.
     */
    private class Connection extends Thread {
        
        /**
         * O poll de sockets de consulta a serem processados.
         */
        private final LinkedList<Socket> SOCKET_LIST = new LinkedList<Socket>();
        
        /**
         * Semáforo que controla o pool de sockets.
         */
        private final Semaphore SOCKET_SEMAPHORE = new Semaphore(0);
        
        public Connection() {
            super("SPFTCP" + (CONNECTION_COUNT+1));
            // Toda connexão recebe prioridade mínima.
            setPriority(Thread.MIN_PRIORITY);
        }
        
        /**
         * Processa um socket de consulta.
         * @param socket o socket de consulta a ser processado.
         */
        private synchronized void process(Socket socket) {
            SOCKET_LIST.offer(socket);
            if (isAlive()) {
                // Libera o próximo processamento.
                SOCKET_SEMAPHORE.release();
            } else {
                // Inicia a thread pela primmeira vez.
                start();
            }
        }
        
        /**
         * Fecha esta conexão liberando a thread.
         */
        private void close() {
            Server.logDebug("Closing " + getName() + "...");
            SOCKET_SEMAPHORE.release();
        }
        
        /**
         * Aguarda nova chamada.
         */
        private void waitCall() {
            try {
                SOCKET_SEMAPHORE.acquire();
            } catch (InterruptedException ex) {
                Server.logError(ex);
            }
        }
        
        /**
         * Processamento da consulta e envio do resultado.
         * Aproveita a thead para realizar procedimentos em background.
         */
        @Override
        public void run() {
            while (!SOCKET_LIST.isEmpty()) {
                try {
                    String query = null;
                    String result = null;
                    Socket socket = SOCKET_LIST.poll();
                    try {
                        InputStream inputStream = socket.getInputStream();
                        InputStreamReader inputStreamReader = new InputStreamReader(inputStream, "ISO-8859-1");
                        BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
                        String line = bufferedReader.readLine();
                        if (line != null) {
                            if (line.equals("/asd") || line.equals("request=smtpd_access_policy")) {
                                // Entrada padrão do Postfix.
                                // Extrair os atributos necessários.
                                String ip = null;
                                String sender = null;
                                String helo = null;
                                query = "";
                                do {
                                    query += line + "\\n";
                                    if (line.startsWith("helo_name=")) {
                                        int index = line.indexOf('=') + 1;
                                        helo = line.substring(index);
                                    } else if (line.startsWith("sender=")) {
                                        int index = line.indexOf('=') + 1;
                                        sender = line.substring(index);
                                    } else if (line.startsWith("client_address=")) {
                                        int index = line.indexOf('=') + 1;
                                        ip = line.substring(index);
                                    }
                                } while ((line = bufferedReader.readLine()).length() > 0);
                                query += "\\n";
                                result = SPF.processPostfixSPF(ip, sender, helo);
                            } else {
                                query = line.trim();
                                result = SPF.processSPF(query);
                            }
                            // Enviando resposta.
                            OutputStream outputStream = socket.getOutputStream();
                            outputStream.write(result.getBytes("ISO-8859-1"));
                            // Mede o tempo de resposta para estatísticas.
                        }
                    } finally {
                        // Fecha conexão logo após resposta.
                        socket.close();
                        // Log da consulta com o respectivo resultado.
                        Server.logQuery(
                                "SPFQR",
                                socket.getInetAddress(),
                                query, result);
                    }
                } catch (Exception ex) {
                    Server.logError(ex);
                } finally {
                    // Atualiza registro mais consultado.
                    SPF.tryBackugroundRefresh();
                    Server.tryBackugroundRefresh();
                    // Oferece a conexão ociosa na última posição da lista.
                    CONNECTION_POLL.offer(this);
                    CONNECION_SEMAPHORE.release();
                    // Aguarda nova chamada.
                    waitCall();
                }
            }
        }
    }
    
    /**
     * Pool de conexões ativas.
     */
    private final LinkedList<Connection> CONNECTION_POLL = new LinkedList<Connection>();
    
    /**
     * Semáforo que controla o pool de conexões.
     */
    private final Semaphore CONNECION_SEMAPHORE = new Semaphore(0);
    
    /**
     * Quantidade total de conexões intanciadas.
     */
    private int CONNECTION_COUNT = 0;
    
    /**
     * Coleta uma conexão ociosa.
     * @return uma conexão ociosa ou nulo se exceder o tempo.
     */
    private Connection pollConnection() {
        if (CONNECION_SEMAPHORE.tryAcquire()) {
            return CONNECTION_POLL.poll();
        } else {
            // Cria uma nova conexão se não houver conecxões ociosas.
            // O servidor aumenta a capacidade conforme a demanda.
            Server.logDebug("Creating SPFTCP" + (CONNECTION_COUNT+1) + "...");
            Connection connection = new Connection();
            CONNECTION_COUNT++;
            return connection;
        }
    }
    
    /**
     * Inicialização do serviço.
     */
    @Override
    public synchronized void run() {
        try {
            Server.logDebug("Listening queries on SPF port " + PORT + "...");
            while (continueListenning()) {
                try {
                    Socket socket = SERVER_SOCKET.accept();
                    Connection connection = pollConnection();
                    if (connection == null) {
                        String result = "ERROR: TOO MANY CONNECTIONS\n";
                        try {
                            OutputStream outputStream = socket.getOutputStream();
                            outputStream.write(result.getBytes("ISO-8859-1"));
                        } finally {
                            socket.close();
                            System.out.print(result);
                        }
                    } else {
                        connection.process(socket);
                    }
                } catch (SocketException ex) {
                    // Conexão fechada externamente pelo método close().
                }
            }
        } catch (Exception ex) {
            Server.logError(ex);
        } finally {
            Server.logDebug("Querie SPF server closed.");
        }
    }
    
    @Override
    protected void close() throws Exception {
        while (CONNECTION_COUNT > 0) {
            CONNECION_SEMAPHORE.acquire();
            Connection connection = CONNECTION_POLL.poll();
            connection.close();
            CONNECTION_COUNT--;
        }
        Server.logDebug("Unbinding querie SPF socket on port " + PORT + "...");
        SERVER_SOCKET.close();
        
    }
}
