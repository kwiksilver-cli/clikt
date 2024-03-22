package com.github.ajalt.clikt.completion

import com.github.ajalt.clikt.completion.CompletionCandidates.Custom.ShellType
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.Option

internal object BashCompletionGenerator {
    fun generateBashOrZshCompletion(command: CliktCommand, zsh: Boolean): String {
        val commandName = command.commandName
        val (isTopLevel, funcName) = commandCompletionFuncName(command)
        val options = command._options
            .filterNot { it.hidden }
            .map { Triple(it.allNames, it.completionCandidates, it.nvalues) }
        val arguments = command._arguments.map { it.name to it.completionCandidates }
        val subcommands = command._subcommands.map { it.commandName }
        val fixedArgNameArray = command._arguments
            .takeWhile { it.nvalues > 0 }
            .flatMap { arg -> (1..arg.nvalues).map { "'${arg.name}'" } }
            .joinToString(" ")
        val varargName = command._arguments.find { it.nvalues < 0 }?.name.orEmpty()
        val paramsWithCandidates =
            (options.map { o -> o.first.maxByOrNull { it.length }!! to o.second } + arguments)

        if (options.isEmpty() && subcommands.isEmpty() && arguments.isEmpty()) return ""

        return buildString {
            if (isTopLevel) {
                append(
                    """
                |#!/usr/bin/env ${if (zsh) "zsh" else "bash"}
                |# Command completion for $commandName
                |# Generated by Clikt
                |
                |
                """.trimMargin()
                )

                if (zsh) {
                    append(
                        """
                    |autoload -Uz compinit
                    |compinit
                    |autoload -Uz bashcompinit
                    |bashcompinit
                    |
                    |
                    """.trimMargin()
                    )
                }

                append(
                    """
                |__skip_opt_eq() {
                |    # this takes advantage of the fact that bash functions can write to local
                |    # variables in their callers
                |    (( i = i + 1 ))
                |    if [[ "${'$'}{COMP_WORDS[${'$'}i]}" == '=' ]]; then
                |          (( i = i + 1 ))
                |    fi
                |}
                |
                """.trimMargin()
                )
            }

            // Generate functions for any custom completions
            for ((name, candidate) in paramsWithCandidates) {
                val body =
                    (candidate as? CompletionCandidates.Custom)?.generator?.invoke(ShellType.BASH)
                        ?: continue
                val indentedBody = body.trimIndent().prependIndent("  ")
                append(
                    """
                |
                |${customParamCompletionName(funcName, name)}() {
                |$indentedBody
                |}
                |
                """.trimMargin()
                )
            }

            // Generate the main completion function for this command
            append(
                """
            |
            |$funcName() {
            |  local i=${if (isTopLevel) "1" else "$" + "1"}
            |  local in_param=''
            |  local fixed_arg_names=($fixedArgNameArray)
            |  local vararg_name='$varargName'
            |  local can_parse_options=1
            |
            |  while [[ ${'$'}{i} -lt ${'$'}COMP_CWORD ]]; do
            |    if [[ ${'$'}{can_parse_options} -eq 1 ]]; then
            |      case "${'$'}{COMP_WORDS[${'$'}i]}" in
            |        --)
            |          can_parse_options=0
            |          (( i = i + 1 ));
            |          continue
            |          ;;
            |
            """.trimMargin()
            )

            for ((names, _, nargs) in options) {
                append("        ")
                names.joinTo(this, "|", postfix = ")\n")
                append("          __skip_opt_eq\n")
                if (nargs.first > 0) {
                    append("          (( i = i + ${nargs.first} ))\n")
                    append("          [[ \${i} -gt COMP_CWORD ]] && in_param='${names.maxByOrNull { it.length }}' || in_param=''\n")
                } else {
                    append("          in_param=''\n")
                }

                append(
                    """
                |          continue
                |          ;;
                |
                """.trimMargin()
                )
            }

            append(
                """
            |      esac
            |    fi
            |    case "${'$'}{COMP_WORDS[${'$'}i]}" in
            |
            """.trimMargin()
            )

            for ((name, toks) in command.aliases()) {
                append(
                    """
                |      $name)
                |        (( i = i + 1 ))
                |        COMP_WORDS=( "${'$'}{COMP_WORDS[@]:0:${'$'}{i}}"
                """.trimMargin()
                )
                toks.joinTo(this, " ", prefix = " ") { "'$it'" }
                append(""" "${'$'}{COMP_WORDS[@]:${'$'}{i}}" )""").append("\n")
                append("        (( COMP_CWORD = COMP_CWORD + ${toks.size} ))\n")

                if (!command.currentContext.allowInterspersedArgs) {
                    append("        can_parse_options=0\n")
                }

                append("        ;;\n")
            }


            for (sub in command._subcommands) {
                append(
                    """
                |      ${sub.commandName})
                |        ${commandCompletionFuncName(sub).second} ${'$'}(( i + 1 ))
                |        return
                |        ;;
                |
                """.trimMargin()
                )
            }

            append(
                """
            |      *)
            |        (( i = i + 1 ))
            |        # drop the head of the array
            |        fixed_arg_names=("${'$'}{fixed_arg_names[@]:1}")
            |
            """.trimMargin()
            )

            if (!command.currentContext.allowInterspersedArgs) {
                append("        can_parse_options=0\n")
            }

            append(
                """
            |        ;;
            |    esac
            |  done
            |  local word="${'$'}{COMP_WORDS[${'$'}COMP_CWORD]}"
            |
            """.trimMargin()
            )

            if (options.isNotEmpty()) {
                val prefixChars = options.flatMap { it.first }
                    .mapTo(mutableSetOf()) { it.first().toString() }
                    .joinToString("")
                append(
                    """
                |  if [[ "${"$"}{word}" =~ ^[$prefixChars] ]]; then
                |    COMPREPLY=(${'$'}(compgen -W '
                """.trimMargin()
                )
                options.flatMap { it.first }.joinTo(this, " ")
                append(
                    """' -- "${"$"}{word}"))
                |    return
                |  fi
                |
                 """.trimMargin()
                )
            }

            append(
                """
            |
            |  # We're either at an option's value, or the first remaining fixed size
            |  # arg, or the vararg if there are no fixed args left
            |  [[ -z "${"$"}{in_param}" ]] && in_param=${"$"}{fixed_arg_names[0]}
            |  [[ -z "${"$"}{in_param}" ]] && in_param=${"$"}{vararg_name}
            |
            |  case "${"$"}{in_param}" in
            |
            """.trimMargin()
            )

            for ((name, completion) in paramsWithCandidates) {
                append(
                    """
                |    $name)
                |
                """.trimMargin()
                )
                when (completion) {
                    CompletionCandidates.None -> {
                    }

                    CompletionCandidates.Path -> {
                        append("       COMPREPLY=(\$(compgen -o default -- \"\${word}\"))\n")
                    }

                    CompletionCandidates.Hostname -> {
                        append("       COMPREPLY=(\$(compgen -A hostname -- \"\${word}\"))\n")
                    }

                    CompletionCandidates.Username -> {
                        append("       COMPREPLY=(\$(compgen -A user -- \"\${word}\"))\n")
                    }

                    is CompletionCandidates.Fixed -> {
                        append("      COMPREPLY=(\$(compgen -W '")
                        completion.candidates.joinTo(this, " ")
                        append("' -- \"\${word}\"))\n")
                    }

                    is CompletionCandidates.Custom -> {
                        if (completion.generator(ShellType.BASH) != null) {
                            // redirect stderr to /dev/null, because bash prints a warning that
                            // "compgen -F might not do what you expect"
                            val fname = customParamCompletionName(funcName, name)
                            append("       COMPREPLY=(\$(compgen -F $fname 2>/dev/null))\n")
                        }
                    }
                }

                append("      ;;\n")
            }

            if (subcommands.isNotEmpty()) {
                append(
                    """
                |    *)
                |      COMPREPLY=(${"$"}(compgen -W '
                """.trimMargin()
                )
                subcommands.joinTo(this, " ")
                append(
                    """' -- "${"$"}{word}"))
                |      ;;
                |
                """.trimMargin()
                )
            }

            append(
                """
            |  esac
            |}
            |
            """.trimMargin()
            )

            for (subcommand in command._subcommands) {
                append(generateBashOrZshCompletion(subcommand, zsh))
            }

            if (isTopLevel) {
                append("\ncomplete -F $funcName $commandName")
            }
        }
    }

    private fun commandCompletionFuncName(command: CliktCommand): Pair<Boolean, String> {
        val ancestors = generateSequence(command.currentContext) { it.parent }
            .map { it.command.commandName }
            .toList().asReversed()
        val isTopLevel = ancestors.size == 1
        val funcName = ancestors.joinToString("_", prefix = "_").replace('-', '_')
        return isTopLevel to funcName
    }

    private fun customParamCompletionName(commandFuncName: String, name: String): String {
        return "_${commandFuncName}_complete_${Regex("[^a-zA-Z0-9]").replace(name, "_")}"
    }

    private val Option.allNames get() = names + secondaryNames
}
