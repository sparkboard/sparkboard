(ns electric.user) ; Must be ".clj" file, Clojure doesn't auto-load user.cljc

; lazy load dev stuff - for faster REPL startup and cleaner dev classpath
(def start-electric-server! (delay @(requiring-resolve 'electric.electric-server-java8-jetty9/start-server!)))
(def shadow-start! (delay @(requiring-resolve 'shadow.cljs.devtools.server/start!)))
(def shadow-watch (delay @(requiring-resolve 'shadow.cljs.devtools.api/watch)))

(def electric-server-config
  {:host "0.0.0.0", :port 3001, :resources-path "public"})

(defn main [& args]
  (println "Starting Electric compiler and server...")
  (@shadow-start!) ; serves index.html as well
  (@shadow-watch :electric-dev) ; depends on shadow server
  ; Shadow loads app.todo-list here, such that it shares memory with server.
  (def server (@start-electric-server! electric-server-config))
  (comment (.stop server)))

; Server-side Electric userland code is lazy loaded by the shadow build.
; WARNING: make sure your REPL and shadow-cljs are sharing the same JVM!

(comment
 (main) ; Electric Clojure(JVM) REPL entrypoint
 (hyperfiddle.rcf/enable!) ; turn on RCF after all transitive deps have loaded
 (shadow.cljs.devtools.api/repl :electric-dev) ; shadow server hosts the cljs repl
 ; connect a second REPL instance to it
 ; (DO NOT REUSE JVM REPL it will fail weirdly)
 (type 1))