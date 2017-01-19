(ns funky-server.aleph-core  
  (:require [clojure.java.io :as io]
            [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [aleph.http :as http]
            [aleph.tcp :as tcp]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [manifold.stream :as s]))

(def system-newline ;; This is in clojure.core but marked private.
  (System/getProperty "line.separator"))

(def non-websocket-request
  {:status 400
   :headers {"content-type" "application/text"}
   :body "Expected a websocket request."})

(defn- handle-message [msg]
  ;;(log/info (String. msg))
  (json/read-str (String. msg) :key-fn keyword))

(defn- init-socket [stream]
  (log/info "Initing new async socket")
  (let [in-ch (async/chan (async/sliding-buffer 1) (map handle-message) #(log/error "Error in received message" %))
        out-ch (async/chan 8 (map #(.getBytes (str (json/write-str %) system-newline))) #(log/error "Error in sent message" %))
        public-socket {:in in-ch :out out-ch}]
    
    (s/connect stream in-ch)
    (s/connect out-ch stream)

    (log/info "New async socket opened")
    public-socket))

(defn init-websocket [req]
  (log/info "Init websocket")
  (if-let [socket (try
                    @(http/websocket-connection req {:headers {:Sec-WebSocket-Protocol "binary"}})
                    (catch Exception e nil))]
    socket
    (do 
        (log/info "Not websocket")
        non-websocket-request)))

(defn socket-server [port]
  (let [connections (async/chan 50 (map init-socket))
  		  aleph-server (tcp/start-server (fn [s _] (async/>!! connections s)) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :connections connections :server aleph-server}))

(defn websocket-server [port]
  (let [connections (async/chan 50 (map init-socket))
        aleph-server (http/start-server #(let [s (init-websocket %)] (log/info "Got connection") (async/>!! connections s) s) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :connections connections :server aleph-server}))

(defn start-game [max-players id step-time]
  (log/info "Starting game with id" id ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        out (async/chan)
        out-mult (async/mult out)
        step (atom 0)
        start-time (l/local-now)]
    
    (async/pipeline 1 out (map #(assoc % :step @step)) in)
    
    (async/go-loop []
      (let [run-time (t/in-millis (t/interval start-time (l/local-now)))
            step-time (- step-time (mod run-time step-time))]
        (async/<! (async/timeout step-time))
        ;;(log/info (str "step " @step " " (l/local-now)))
        (swap! step inc)
        (async/>! out {:lock (dec @step)})
        (recur)))
    
    {:in in 
     :out-mult out-mult 
     :players 0
     :next-player-id 0
     :max-players max-players 
     :id id 
     :close #(do (async/close! out) (async/close! in))}))


(defn add-player [player-socket game]
  (log/info "Add player to game" (:id game) "with" (:players game) "players")
  (async/pipe (:in player-socket) (:in game) false)
  (async/>!! (:out player-socket) {:newGame (= (:players game) 0) :playerId (:next-player-id game)})
  (async/tap (:out-mult game) (:out player-socket))
  (async/>!! (:in game) {:msg "New player joined" :players (inc (:players game))})
  (-> game
      (update :players inc)
      (update :next-player-id inc)))


(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn join-game [games player-socket]
  (let [{:keys [id maxPlayers stepTime]} (async/<!! (:in player-socket))]
    (if-let [i (first (indices #(= (:id %) id) games))]
      (assoc games i (add-player player-socket (nth games i)))
      (->> (start-game maxPlayers id stepTime)
           (add-player player-socket)
           (conj games)))))

  
(defn start-lockstep-server [socket-server websocket-server]
  (async/go (async/reduce join-game [] (:connections websocket-server)))
  (async/reduce join-game [] (:connections socket-server)))

(defn echo-handler [s info]
  (s/connect s s))

(defn -main []
  (tcp/start-server echo-handler {:port 10001})
  (async/<!! (start-lockstep-server (socket-server 8888) (websocket-server 8889))))


;;(.close (:server server))
;;(def server (socket-server 8888))
;;(start-lockstep-server server)

