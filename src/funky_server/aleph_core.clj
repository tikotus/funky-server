(ns funky-server.aleph-core
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [clojure.data.json :as json]
            [aleph.http :as http]
            [gloss.core :as gloss]
            [gloss.io :as gloss.io]
            [aleph.tcp :as tcp]
            [clj-time.local :as l]
            [clj-time.core :as t]
            [manifold.stream :as s]))

(defn new-uuid
  "Retrieve a type 4 (pseudo randomly generated) UUID. The UUID is generated using a cryptographically strong pseudo random number generator."
  []
  (str (java.util.UUID/randomUUID)))

(def system-newline ;; This is in clojure.core but marked private.
  (System/getProperty "line.separator"))

(defn- init-player [stream]
  (let [last-msg-time (atom (l/local-now))
        handle-message #(do (reset! last-msg-time (l/local-now)) (json/read-str % :key-fn keyword))
        in-ch (async/chan (async/sliding-buffer 64) (map handle-message) #(log/error "Error in received message" %))
        out-ch (async/chan (async/dropping-buffer 256) (map #(json/write-str %)) #(log/error "Error in sent message" %))
        out-raw-ch (async/chan (async/dropping-buffer 256))
        player {:in-local (async/chan) :in in-ch :out out-ch :out-raw out-raw-ch :last-msg-time last-msg-time}]

    (async/pipe out-ch out-raw-ch)
    (s/connect stream in-ch)
    (s/connect out-raw-ch stream {:upstream? true})

    (async/go-loop []
      (async/<! (async/timeout 1000))
      (if (> (t/in-millis (t/interval @last-msg-time (l/local-now))) 30000)
        (when-not (s/closed? stream) (s/close! stream))
        (recur)))

    (log/info "New player initialized")
    player))

(defn init-websocket [req]
  (if-let [socket (try
                    @(http/websocket-connection req {:headers {:Sec-WebSocket-Protocol "binary"}})
                    (catch Exception e nil))]
    socket
    (do
        (log/info "Not websocket")
        nil)))

(defn wait-for-disconnect [stream player]
  (let [done (async/chan)]
    (async/go
      (s/on-closed stream #(async/put! done (assoc player :disconnected? true)))
      (async/<! done))))


(defn handshake [player]
  (let [id (new-uuid)
        player (assoc player :id id)]
    (async/go
      (async/>! (:out player) {:msg "Welcome!" :id id})
      (loop []
        (let [msg (async/<! (:in player))]
          (if (nil? msg)
            nil
            (if (every? msg #{:gameType :maxPlayers :stepTime})
              (assoc player :game-info (clojure.set/rename-keys msg {:gameType :game-type :maxPlayers :max-players :stepTime :step-time}))
              (recur))))))))

(defn stream-write [out value]
  (async/go
    (async/>! out value)
    value))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn wrap-duplex-stream [protocol s]
  (let [out (s/stream)]
    (s/connect
      (s/map #(gloss.io/encode protocol %) out)
      s)
    (s/splice
      out
      (gloss.io/decode-stream s protocol))))

(defn handle-new-connection [stream info players]
  (log/info "new connection" info)
  (async/go
    (some->> stream
             (wrap-duplex-stream protocol)
             init-player
             (handshake) (async/<!)
             (stream-write players) (async/<!)
             (wait-for-disconnect stream) (async/<!)
             (stream-write players) (async/<!)
             (log/info "Disconnected player" info))
    (log/info "end connection" info)))


(defn socket-server [port]
  (let [players (async/chan)
  		  aleph-server (tcp/start-server #(handle-new-connection %1 %2 players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(def protocol (gloss/string :utf-8 :delimiters ["\n"]))

(defn websocket-server [port]
  (let [players (async/chan)
        aleph-server (http/start-server #(handle-new-connection (init-websocket %1) "websocket" players) {:port port})]
    (log/info "Starting async server at port" port)
    { :port port :players players :server aleph-server}))

(defn start-ticker [step step-time out done join-ch]
  (let [start-time (l/local-now)]
    (async/go-loop []
      (let [run-time (t/in-millis (t/interval start-time (l/local-now)))
            step-time (- step-time (mod run-time step-time))]
        (when (-> [done (async/timeout step-time)] async/alts! second (not= done))
          ;;(log/info (str "step " @step " " (l/local-now)))
          (swap! step inc)
          (let [lock-msg {:lock (dec @step)}
                join-msg (async/poll! join-ch)]
            (async/>! out (if (nil? join-msg) [lock-msg] [lock-msg (assoc join-msg :step (dec @step))])))
          (recur))))))

(defn start-game [type max-players step-time]
  (log/info "Starting game with type" type ", max-players" max-players ", step-time" step-time)
  (let [in (async/chan)
        in-mult (async/mult in)
        out (async/chan 1 cat)
        out-mult (async/mult out)
        out-raw (async/tap out-mult (async/chan 1 (map #(json/write-str %)) #(log/error "Error in sent message" %)))
        out-raw-mult (async/mult out-raw)
        out-sync (async/tap in-mult (async/chan (async/dropping-buffer 1) (filter #(-> % :msg (= "sync")))))
        out-sync-mult (async/mult out-sync)
        join-ch (async/chan 8)
        step (atom 0)
        done (async/chan)
        alive-filter (filter #(-> % :alive nil?))
        sync-filter (filter #(-> % :msg (not= "sync")))
        map-vec (map #(identity [%]))]

    (if (zero? step-time)
      (do
        (async/pipeline 1 out (comp alive-filter map-vec) (async/tap in-mult (async/chan))) ;; simple case for stepless games
        (async/pipeline 1 out map-vec join-ch))
      (do
        (async/pipeline 1 out (comp alive-filter sync-filter map-vec) (async/tap in-mult (async/chan)))
        (start-ticker step step-time out done join-ch)))

    {:in in
     :join-ch join-ch
     :out-mult out-mult
     :out-raw-mult out-raw-mult
     :out-sync-mult out-sync-mult
     :players #{}
     :synced-players (atom [])
     :next-player-id 0
     :max-players max-players
     :type type
     :done done
     :seed (rand-int 500000) ;; something big but avoids overflow
     :close #(do (async/close! out) (async/close! in))}))


(defn active? [player]
  (< (t/in-millis (t/interval @(:last-msg-time player) (l/local-now)))
     2000))

(defn pick-syncer [game]
  (let [active (filter active? @(:synced-players game))]
    (if (> (count active) 0)
      (:id (rand-nth active))
      nil)))

(defn read-one-from-mult [m]
  (async/go
    (let [c (async/tap m (async/chan))
          r (async/<! c)]
      (async/untap m c)
      r)))

(defn request-sync [player game]
  (async/go
    (async/>! (:join-ch game) {:msg "join" :syncer (pick-syncer game)})
    (async/>! (:out player) (async/<! (read-one-from-mult (:out-sync-mult game))))))

(defn add-player [player game]
  (let [playerId (:next-player-id game)
        newGame? (empty? (:players game))]
    (log/info "Add player to game" (:type game) "with players" (:players game))
    (async/go
      (async/>! (:out player) {:join true :newGame newGame? :playerId playerId :seed (:seed game)})
      (async/tap (:out-raw-mult game) (:out-raw player))
      (async/pipeline 1 (:in game) (map #(assoc % :playerId playerId)) (async/merge [(:in player) (:in-local player)]) false)
      (when-not newGame? (async/<! (request-sync player game)))
      (swap! (:synced-players game) #(conj % player)))
    (-> game
        (update :players conj (:id player))
        (update :next-player-id inc))))

(defn remove-player [player game]
  (if (contains? (:players game) (:id player))
    (do
      (async/put! (:in-local player) {:disconnected (:id player)})
      (log/info "Removed player from game" (:type game) "Remaining players" (:players game))
      (swap! (:synced-players game) (fn [players] (filter #(not= (:id %) (:id player)) players)))
      (update game :players disj (:id player)))
    game))

(defn indices [pred coll]
   (keep-indexed #(when (pred %2) %1) coll))

(defn valid-game? [game-info game]
  (and (= (:type game) (:game-type game-info))
       (-> game :players count (< (:max-players game-info)))
       (not (nil? (pick-syncer game)))))

(defn pick-game-index [game-info games]
  (first (indices #(valid-game? game-info %) games)))

(defn join-game [games player]
  (let [game-info (:game-info player)
        {:keys [game-type max-players step-time]} game-info]
    (if-let [i (pick-game-index game-info games)]
      (assoc games i (add-player player (nth games i)))
      (->> (start-game game-type max-players step-time)
           (add-player player)
           (conj games)))))

(defn quit-game [games player]
  (let [games (map #(remove-player player %) games)
        { emptied true existing false } (group-by #(empty? (:players %)) games)]
    (doall (map #(async/close! (:done %)) emptied))
    (or existing [])))


(defn join-or-quit-game [games player]
  (if (:disconnected? player)
    (quit-game games player)
    (join-game games player)))

; NOTE/TODO: We use async/reduce with join-or-quit-game. It is kind of cool
; that the games are not in an atom but game ended up containing atoms anyways
; so putting games in an atom would make sense. It would make a couple of
; things cleaner to implement.
(defn start-lockstep-server [socket-server websocket-server]
  (let [players (async/chan 1)]
    (async/pipe (:players websocket-server) players)
    (async/pipe (:players socket-server) players)
    (async/reduce join-or-quit-game [] players)))

(defn echo-handler [s info]
  (s/connect s s))

(defn -main []
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (log/error ex "Uncaught exception on" (.getName thread)))))
  (tcp/start-server echo-handler {:port 9120})
  (log/info "server done" (async/<!! (start-lockstep-server (socket-server 9121) (websocket-server 9122))))
  (log/info "quit"))
