ThisBuild / versionPolicyIntention := Versioning.BumpMajor

ThisBuild / versionPolicyIgnoredInternalDependencyVersions := Some("^\\d+\\.\\d+\\.\\d+-pre\\.\\d+\\.\\w+".r)

ThisBuild / versionPolicyPreviousVersions := CompatReportPlugin.previousRelease.value.toSeq
