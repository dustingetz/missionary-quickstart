# missionary-quickstart

Livecoding REPL introduction to [Missionary](https://github.com/leonoel/missionary).

* Part 1: building a managed DOM input in ClojureScript, which is the simplest real world use case. [src/quickstart.cljs](https://github.com/dustingetz/missionary-quickstart/blob/main/src/quickstart.cljs)
* Part 2: flow protocol explainer (works in Clojure and ClojureScript). [src/flow_protocol.cljc](https://github.com/dustingetz/missionary-quickstart/blob/main/src/flow_protocol.cljc)

## How to run

* Clojure/Script project
* Clone repo, open in your Clojure editor and jack in as ClojureScript browser REPL
* If you break something while playing around, simply refresh the page, which gives you a clean slate REPL.
* **browser console** has REPL evaluation results. Partial incomplete results may be shown in the editor REPL, this is editor dependent!

![image](https://user-images.githubusercontent.com/124158/279403213-333c73e8-64c5-4c5a-b93b-190cb4645cad.png)

*figure: test reports are in the browser console*

## Calva instructions

* "Start Project REPL and Connect (aka Jack In)"
* "deps.edn + shadow-cljs"
* validate browser REPL with `(type 1) ; => #object[Number]`
* open src/quickstart.cljs
* evaluate ns form
* evaluate first test

https://user-images.githubusercontent.com/124158/279404054-5e7fcb03-283e-4b4a-8ad1-8395f2be2297.mp4
