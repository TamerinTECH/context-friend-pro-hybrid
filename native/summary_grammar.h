#pragma once
// Schema constrains syntax only. Source-reference and factual review remain required.
static const char *summary_grammar = R"gbnf(
root ::= "{" ws "\"title\":" ws string "," ws "\"paragraphs\":" ws statements "," ws "\"decisions\":" ws statements "," ws "\"actions\":" ws actions "," ws "\"uncertainties\":" ws statements "}" ws
statements ::= "[" ws (statement ("," ws statement)*)? "]" ws
statement ::= "{" ws "\"text\":" ws string "," ws "\"sources\":" ws sources "}" ws
actions ::= "[" ws (action ("," ws action)*)? "]" ws
action ::= "{" ws "\"text\":" ws string "," ws "\"owner\":" ws nullable "," ws "\"due\":" ws nullable "," ws "\"sources\":" ws sources "}" ws
nullable ::= string | "null" ws
sources ::= "[" ws string ("," ws string)* "]" ws
string ::= "\"" ([^"\\\x7F\x00-\x1F] | "\\" (["\\bfnrt] | "u" [0-9a-fA-F]{4}))* "\"" ws
ws ::= [ \t\n\r]*
)gbnf";
