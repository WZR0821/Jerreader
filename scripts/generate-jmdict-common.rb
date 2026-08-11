#!/usr/bin/env ruby
# frozen_string_literal: true

# Generates the compact, shared mobile asset from jmdict-simplified's
# jmdict-eng-common JSON release. The source JSON remains governed by the
# EDRDG/JMdict CC BY-SA 4.0 licence; see docs/THIRD_PARTY_DICTIONARIES.md.

require "json"
require "fileutils"

abort "usage: generate-jmdict-common.rb INPUT.json OUTPUT.tsv" unless ARGV.length == 2

input_path, output_path = ARGV
document = JSON.parse(File.binread(input_path))
list_separator = "\u001F"

part_of_speech = {
  "n" => "名词",
  "n-adv" => "副词性名词",
  "n-pref" => "前缀性名词",
  "n-suf" => "后缀性名词",
  "n-t" => "时间名词",
  "pn" => "代词",
  "adj-i" => "い形容词",
  "adj-ix" => "い形容词",
  "adj-na" => "な形容词",
  "adj-no" => "连体修饰词",
  "adj-pn" => "连体词",
  "adv" => "副词",
  "adv-to" => "と副词",
  "aux" => "助动词",
  "aux-adj" => "辅助形容词",
  "aux-v" => "补助动词",
  "conj" => "接续词",
  "cop" => "系词",
  "ctr" => "量词",
  "exp" => "惯用表达",
  "int" => "感叹词",
  "num" => "数词",
  "pref" => "前缀",
  "prt" => "助词",
  "suf" => "后缀",
  "v1" => "一段动词",
  "v5b" => "五段动词",
  "v5g" => "五段动词",
  "v5k" => "五段动词",
  "v5k-s" => "五段动词",
  "v5m" => "五段动词",
  "v5n" => "五段动词",
  "v5r" => "五段动词",
  "v5r-i" => "五段动词",
  "v5s" => "五段动词",
  "v5t" => "五段动词",
  "v5u" => "五段动词",
  "v5u-s" => "五段动词",
  "vi" => "自动词",
  "vk" => "カ变动词",
  "vs" => "サ变动词",
  "vs-i" => "サ变动词",
  "vs-s" => "サ变动词",
  "vt" => "他动词"
}.freeze

def escaped(value)
  value.to_s
       .gsub("\\", "\\\\")
       .gsub("\t", "\\t")
       .gsub("\r", "\\r")
       .gsub("\n", "\\n")
end

FileUtils.mkdir_p(File.dirname(output_path))
File.open(output_path, "wb") do |output|
  output.puts "# Jerreader JMdict common subset"
  metadata = "# JMdict date: %s; converter: jmdict-simplified %s" % [
    document.fetch("dictDate"),
    document.fetch("version")
  ]
  output.puts metadata
  output.puts "# Data: EDRDG/JMdict, CC BY-SA 4.0; see docs/THIRD_PARTY_DICTIONARIES.md"

  document.fetch("words").each do |word|
    kanji = word.fetch("kanji").map { |form| form.fetch("text") }
    kana = word.fetch("kana").map { |form| form.fetch("text") }
    forms = (kanji + kana).uniq
    next if forms.empty?

    common_kanji = word.fetch("kanji").find { |form| form["common"] }
    common_kana = word.fetch("kana").find { |form| form["common"] }
    lemma = common_kanji&.fetch("text") || kanji.first || common_kana&.fetch("text") || kana.first
    reading = common_kana&.fetch("text") || kana.first || lemma

    senses = word.fetch("sense")
    tags = senses.flat_map { |sense| sense.fetch("partOfSpeech") }.uniq.first(3)
    pos = tags.map { |tag| part_of_speech.fetch(tag, tag) }.uniq.join(" / ")
    glosses = senses.flat_map { |sense| sense.fetch("gloss").map { |gloss| gloss.fetch("text") } }
                    .uniq
                    .first(6)
    next if glosses.empty?

    output.puts [
      forms.map { |form| escaped(form) }.join(list_separator),
      escaped(lemma),
      escaped(reading),
      escaped(pos),
      glosses.map { |gloss| escaped(gloss) }.join(list_separator)
    ].join("\t")
  end
end
