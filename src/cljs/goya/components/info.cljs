(ns goya.components.info
  (:require [om.core :as om :include-macros true]
            [om.dom :as omdom :include-macros true])

(defn infocomponent []
  (om/component
    (omdom/div #js {:className "about-info"}
        "Goya is a pixel art editor built using "
        (omdom/a #js {:href "http://clojure.org/clojurescript" :target "_blank"} "ClojureScript")
        " and "
        (omdom/a #js {:href "https://github.com/swannodette/om" :target "_blank"} "Om") "."
        " The spiffy icons are provided by "
        (omdom/a #js {:href "http://fontello.com/" :target "_blank"} "Fontello") "."
        (omdom/p nil
          (omdom/a #js {:href "https://github.com/jackschaedler/goya" :target "_blank"} "View the source on github")
          (omdom/i #js {:className "icon-github-circled"} ""))
        (omdom/p nil
          "If you're looking for some pixelly inspiration, head on over to the nice folks at "
          (omdom/a #js {:href "http://www.pixeljoint.com/" :target "_blank"} "PixelJoint") ".")
        (omdom/p nil
          "Lord Geoffrey Chittlewurst welcomes you to Goya. Have a drink and enjoy making some pixel art!"))))
